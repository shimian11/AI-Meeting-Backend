package com.banma.interview.interview.application.runtime;

import cn.hutool.core.util.StrUtil;
import com.banma.interview.interview.service.model.InterviewTurnLog;
import lombok.Getter;

import java.util.concurrent.ScheduledFuture;

/**
 * 定义单个面试会话的热刷新聚合桶，
 * 用于在本地节点内暂存同一 session 的待刷新意图、定时任务句柄和刷新中状态。
 */
@Getter
public class InterviewSessionRuntimeHotRefreshBucket {

    // 所属面试会话 id
    /** 会话 id */
    private final String sessionId;

    // 待处理的刷新触发类型（同批次取优先级更高者）
    private InterviewSessionRuntimeHotRefreshTrigger pendingTrigger;

    // 待写入的快照级别
    private String pendingSnapshotLevel;

    // 待记录的请求幂等 id
    private String pendingRequestId;

    // 待提交的问答轮次
    private InterviewTurnLog pendingCommittedTurn;

    // 是否需要把轮次写入归档表
    private boolean pendingPersistTurnArchive;

    // 是否要求立即刷盘（关键检查点）
    private boolean pendingForceFlush;

    // 本批次第一个事件的时间戳
    private long firstEventAt;

    // 本批次最近一次事件的时间戳
    private long lastEventAt;

    // 已排定的刷新任务执行时间戳
    private long scheduledFlushAt;

    // 是否有刷新正在执行
    private boolean flushInProgress;

    // 已排定的刷新任务句柄
    private ScheduledFuture<?> scheduledFuture;

    public InterviewSessionRuntimeHotRefreshBucket(String sessionId) {
        this.sessionId = sessionId;
    }

    /**
     * 合并一次热刷新请求：累积时间窗口、按优先级覆盖触发类型，并聚合各类待处理数据。
     */
    public void merge(InterviewSessionRuntimeHotRefreshRequest request) {
        // （1）空请求直接忽略，并确定本次事件时间
        if (request == null) {
            return;
        }
        long now = request.getTriggerAt() > 0 ? request.getTriggerAt() : System.currentTimeMillis();
        if (firstEventAt <= 0L) {
            firstEventAt = now;
        }
        lastEventAt = now;
        // （2）触发类型按优先级覆盖，同时更新快照级别
        if (shouldReplaceTrigger(request.getTrigger())) {
            pendingTrigger = request.getTrigger();
            pendingSnapshotLevel = request.getSnapshotLevel();
        }
        // （3）合并请求 id 与待提交轮次
        if (StrUtil.isNotBlank(request.getRequestId())) {
            pendingRequestId = request.getRequestId().trim();
        }
        if (request.getCommittedTurn() != null) {
            pendingCommittedTurn = request.getCommittedTurn();
        }
        // （4）归档与强制刷盘标记按「或」累积，一旦置位不会被后续请求清除
        pendingPersistTurnArchive = pendingPersistTurnArchive || request.isPersistTurnArchive();
        pendingForceFlush = pendingForceFlush || request.isForceFlush();
    }

    /** 判断桶内是否存在待刷新内容（有触发类型或快照级别）。 */
    public boolean hasPendingRefresh() {
        return pendingTrigger != null || StrUtil.isNotBlank(pendingSnapshotLevel);
    }

    /** 判断本批次是否要求立即刷盘。 */
    public boolean isPendingForceFlush() {
        return pendingForceFlush;
    }

    /** 登记已排定的刷新任务及其执行时间。 */
    public void attachScheduledFuture(ScheduledFuture<?> future, long scheduledFlushAt) {
        this.scheduledFuture = future;
        this.scheduledFlushAt = scheduledFlushAt;
    }

    /** 取消并清空已排定的刷新任务。 */
    public void clearScheduledFuture() {
        // （1）存在任务句柄时取消，并清空登记信息
        if (scheduledFuture != null) {
            scheduledFuture.cancel(false);
        }
        scheduledFuture = null;
        scheduledFlushAt = 0L;
    }

    /**
     * 开始一次刷新：置位刷新中标记、取消定时任务，并用待处理字段组装刷新请求。
     *
     * @return 待执行的刷新请求，已有刷新在执行或无待刷新内容时返回 null
     */
    public InterviewSessionRuntimeHotRefreshRequest startFlush() {
        // （1）已在进行中或无待刷新内容时不再启动
        if (flushInProgress || !hasPendingRefresh()) {
            return null;
        }
        // （2）置位刷新中并取消已排定的任务
        flushInProgress = true;
        clearScheduledFuture();
        // （3）用当前待处理字段组装刷新请求
        InterviewSessionRuntimeHotRefreshRequest request = InterviewSessionRuntimeHotRefreshRequest.builder()
                .sessionId(sessionId)
                .trigger(pendingTrigger)
                .snapshotLevel(pendingSnapshotLevel)
                .requestId(pendingRequestId)
                .committedTurn(pendingCommittedTurn)
                .persistTurnArchive(pendingPersistTurnArchive)
                .forceFlush(pendingForceFlush)
                .triggerAt(lastEventAt > 0 ? lastEventAt : System.currentTimeMillis())
                .build();
        // （4）清空待处理状态后返回请求
        resetPendingState();
        return request;
    }

    /** 标记本次刷新结束。 */
    public void completeFlush() {
        flushInProgress = false;
    }

    /**
     * 刷新失败时把请求内容重新合并回桶内，并强制下一次立即重试。
     */
    public void restoreFailedRequest(InterviewSessionRuntimeHotRefreshRequest request) {
        // （1）请求为空时忽略
        if (request == null) {
            return;
        }
        // （2）重新合并内容并置位强制刷盘
        merge(request);
        pendingForceFlush = true;
    }

    /**
     * 计算下一次刷新的时间点：取「防抖窗口」与「最大聚合窗口」中较早者，避免无限延迟。
     */
    public long computeScheduledFlushAt(long now, long debounceWindowMillis, long maxAggregateWindowMillis) {
        // （1）以首末事件时间为基础计算两个候选执行时间
        long firstAt = firstEventAt > 0 ? firstEventAt : now;
        long lastAt = lastEventAt > 0 ? lastEventAt : now;
        long debounceAt = lastAt + Math.max(debounceWindowMillis, 0L);
        long maxAt = firstAt + Math.max(maxAggregateWindowMillis, 0L);
        // （2）取较早者，保证最长等待不超过最大聚合窗口
        return Math.min(debounceAt, maxAt);
    }

    /**
     * 判断新触发类型是否应替换当前待处理触发：同优先级或更高时替换。
     */
    private boolean shouldReplaceTrigger(InterviewSessionRuntimeHotRefreshTrigger trigger) {
        // （1）新触发为空时不替换
        if (trigger == null) {
            return false;
        }
        // （2）当前无待处理触发时直接接受
        if (pendingTrigger == null) {
            return true;
        }
        // （3）按优先级比较
        return trigger.getPriority() >= pendingTrigger.getPriority();
    }

    /** 清空所有待处理字段与时间窗口。 */
    private void resetPendingState() {
        pendingTrigger = null;
        pendingSnapshotLevel = null;
        pendingRequestId = null;
        pendingCommittedTurn = null;
        pendingPersistTurnArchive = false;
        pendingForceFlush = false;
        firstEventAt = 0L;
        lastEventAt = 0L;
    }
}
