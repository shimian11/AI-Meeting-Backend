package com.banma.interview.interview.application.runtime;

import cn.hutool.core.util.StrUtil;
import com.banma.interview.interview.config.InterviewHotRefreshConfiguration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * 提供面试会话热快照合并写与防抖刷新的本地协调能力，
 * 用于按 session 聚合热刷新意图、延迟普通中间态刷盘并保证关键检查点立即落盘。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewSessionRuntimeHotRefreshCoordinator {

    /** 快照热刷新配置 */
    private final InterviewHotRefreshConfiguration hotRefreshConfiguration;

    /** 运行态快照读写与刷新触发 */
    private final InterviewSessionRuntimeSnapshotService runtimeSnapshotService;

    @Qualifier("scheduledExecutorService")
    /** 定时调度线程池 */
    private final ScheduledExecutorService scheduledExecutorService;

    private final Map<String, InterviewSessionRuntimeHotRefreshBucket> pendingBuckets = new ConcurrentHashMap<>();

    /**
     * 提交一次热刷新请求：按开关、强制刷盘标记选择同步落盘、立即刷新或防抖合并刷新。
     */
    public void submit(InterviewSessionRuntimeHotRefreshRequest request) {
        // （1）请求或会话 id 为空时忽略
        if (request == null || StrUtil.isBlank(request.getSessionId())) {
            return;
        }
        // （2）热刷新开关关闭时直接同步落盘
        if (!Boolean.TRUE.equals(hotRefreshConfiguration.getEnable())) {
            runtimeSnapshotService.flushHotRefreshRequest(request);
            return;
        }
        // （3）强制刷盘请求立即执行，其余走防抖合并
        if (request.isForceFlush()) {
            submitForceFlush(request);
            return;
        }
        submitDeferred(request);
    }

    /**
     * 防抖提交：把请求合并进会话桶，并在无刷新进行时安排下一次定时刷新。
     */
    private void submitDeferred(InterviewSessionRuntimeHotRefreshRequest request) {
        // （1）按会话 id 获取或创建聚合桶
        InterviewSessionRuntimeHotRefreshBucket bucket = pendingBuckets.computeIfAbsent(
                request.getSessionId(),
                InterviewSessionRuntimeHotRefreshBucket::new
        );
        synchronized (bucket) {
            // （2）合并请求；已有刷新在执行时由该次刷新收尾时再排定
            bucket.merge(request);
            if (bucket.isFlushInProgress()) {
                return;
            }
            // （3）按防抖窗口安排定时刷新
            scheduleFlushLocked(bucket, computeDebounceDelayLocked(bucket));
        }
    }

    /**
     * 强制刷新：合并请求并等待正在进行的刷新结束，然后立即执行一次刷新。
     */
    private void submitForceFlush(InterviewSessionRuntimeHotRefreshRequest request) {
        // （1）按会话 id 获取或创建聚合桶
        InterviewSessionRuntimeHotRefreshBucket bucket = pendingBuckets.computeIfAbsent(
                request.getSessionId(),
                InterviewSessionRuntimeHotRefreshBucket::new
        );
        boolean flushNow = false;
        synchronized (bucket) {
            // （2）合并请求并取消已排定的防抖任务
            bucket.merge(request);
            bucket.clearScheduledFuture();
            // （3）在超时时间内等待当前刷新结束，避免并发落盘
            long waitDeadline = System.currentTimeMillis() + positive(hotRefreshConfiguration.getForceFlushWaitMillis(), 600L);
            while (bucket.isFlushInProgress() && System.currentTimeMillis() < waitDeadline) {
                try {
                    long remaining = waitDeadline - System.currentTimeMillis();
                    bucket.wait(Math.min(Math.max(remaining, 1L), 50L));
                } catch (InterruptedException ex) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            flushNow = !bucket.isFlushInProgress();
        }
        // （4）确认无刷新在跑时立即刷盘
        if (flushNow) {
            flushBucket(request.getSessionId(), bucket);
        }
    }

    /**
     * 执行桶内刷新循环：逐批取出待刷新请求落盘，失败回填并按需立即重试或延后重试。
     */
    private void flushBucket(String sessionId, InterviewSessionRuntimeHotRefreshBucket bucket) {
        int immediateAttempts = 0;
        while (true) {
            InterviewSessionRuntimeHotRefreshRequest flushRequest;
            synchronized (bucket) {
                // （1）取出本批次待刷新请求，无内容时清理空桶后结束
                flushRequest = bucket.startFlush();
                if (flushRequest == null) {
                    cleanupIfIdle(sessionId, bucket);
                    return;
                }
            }
            // （2）执行真正的落盘
            boolean success = runtimeSnapshotService.flushHotRefreshRequest(flushRequest);
            boolean continueImmediately = false;
            synchronized (bucket) {
                // （3）结束本次刷新并唤醒等待者，失败时把请求回填到桶内
                bucket.completeFlush();
                bucket.notifyAll();
                if (!success) {
                    bucket.restoreFailedRequest(flushRequest);
                }
                // （4）无剩余待刷新内容时清理空桶并结束
                if (!bucket.hasPendingRefresh()) {
                    cleanupIfIdle(sessionId, bucket);
                    return;
                }
                // （5）需要立即重试（强制刷盘或失败）时继续循环，否则重新排定定时任务
                if ((bucket.isPendingForceFlush() || !success)
                        && immediateAttempts < positive(hotRefreshConfiguration.getMaxImmediateFlushAttempts(), 2)) {
                    continueImmediately = true;
                    immediateAttempts++;
                } else {
                    long scheduleDelay = success
                            ? computeDebounceDelayLocked(bucket)
                            : positive(hotRefreshConfiguration.getFailureRetryDelayMillis(), 200L);
                    scheduleFlushLocked(bucket, scheduleDelay);
                }
            }
            if (!continueImmediately) {
                return;
            }
        }
    }

    /**
     * 在桶锁内排定下一次刷新任务，替换掉旧的定时任务。
     */
    private void scheduleFlushLocked(InterviewSessionRuntimeHotRefreshBucket bucket, long delayMillis) {
        // （1）取消旧任务并计算实际执行时间
        bucket.clearScheduledFuture();
        long safeDelay = Math.max(delayMillis, 0L);
        long scheduledAt = System.currentTimeMillis() + safeDelay;
        // （2）提交定时任务并登记句柄
        ScheduledFuture<?> future = scheduledExecutorService.schedule(
                () -> flushBucket(bucket.getSessionId(), bucket),
                safeDelay,
                TimeUnit.MILLISECONDS
        );
        bucket.attachScheduledFuture(future, scheduledAt);
    }

    /**
     * 按防抖窗口与最大聚合窗口计算本次刷新的延迟时间。
     */
    private long computeDebounceDelayLocked(InterviewSessionRuntimeHotRefreshBucket bucket) {
        // （1）计算目标执行时间，并换算为相对当前时间的延迟
        long now = System.currentTimeMillis();
        long scheduledAt = bucket.computeScheduledFlushAt(
                now,
                positive(hotRefreshConfiguration.getDebounceWindowMillis(), 150L),
                positive(hotRefreshConfiguration.getMaxAggregateWindowMillis(), 500L)
        );
        return Math.max(scheduledAt - now, 0L);
    }

    /**
     * 桶已空闲（无刷新中、无待刷新、无未完成任务）时从登记表移除，避免内存堆积。
     */
    private void cleanupIfIdle(String sessionId, InterviewSessionRuntimeHotRefreshBucket bucket) {
        synchronized (bucket) {
            // （1）仍有刷新或待处理内容时保留
            if (bucket.isFlushInProgress() || bucket.hasPendingRefresh()) {
                return;
            }
            // （2）存在尚未结束的定时任务时保留
            if (bucket.getScheduledFuture() != null && !bucket.getScheduledFuture().isDone()) {
                return;
            }
        }
        // （3）确认空闲后移除
        pendingBuckets.remove(sessionId, bucket);
    }

    private long positive(Long value, long defaultValue) {
        return value != null && value > 0L ? value : defaultValue;
    }

    private int positive(Integer value, int defaultValue) {
        return value != null && value > 0 ? value : defaultValue;
    }
}
