package com.banma.interview.interview.application.guard.singleflight.service;

import cn.hutool.core.util.StrUtil;
import com.banma.interview.interview.application.guard.core.InterviewAiGuardException;
import com.banma.interview.interview.application.guard.singleflight.cache.FlightReplayLocalCache;
import com.banma.interview.interview.application.guard.singleflight.cache.FlightResultSerializer;
import com.banma.interview.interview.application.guard.singleflight.coordinator.FlightCoordinatorRepository;
import com.banma.interview.interview.application.guard.singleflight.coordinator.FlightHeartbeatManager;
import com.banma.interview.interview.application.guard.singleflight.coordinator.FlightNotificationService;
import com.banma.interview.interview.application.guard.singleflight.model.FlightAcquireResult;
import com.banma.interview.interview.application.guard.singleflight.model.FlightErrorType;
import com.banma.interview.interview.application.guard.singleflight.model.FlightMetaSnapshot;
import com.banma.interview.interview.application.guard.singleflight.model.FlightMode;
import com.banma.interview.interview.application.guard.singleflight.model.FlightOwnerContext;
import com.banma.interview.interview.application.guard.singleflight.model.FlightStatus;
import com.banma.interview.interview.application.guard.singleflight.model.FlightStoredResult;
import com.banma.interview.interview.config.InterviewAiSingleFlightConfiguration;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.CompletionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

/**
 * 分布式 AI single-flight 核心服务，负责在集群内协调 owner 与 follower，
 * 完成请求抢占、结果复用、失败接管以及本地降级回退。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DistributedInterviewAiSingleFlightService {

    /** single-flight 配置 */
    private final InterviewAiSingleFlightConfiguration configuration;
    /** 本地 single-flight */
    private final InterviewAiSingleFlightService localSingleFlightService;
    /** single-flight 协调记录仓储 */
    private final FlightCoordinatorRepository flightCoordinatorRepository;
    /** single-flight 完成通知 */
    private final FlightNotificationService flightNotificationService;
    /** single-flight 心跳续约 */
    private final FlightHeartbeatManager flightHeartbeatManager;
    /** single-flight 结果序列化 */
    private final FlightResultSerializer flightResultSerializer;
    /** single-flight 本地回放缓存 */
    private final FlightReplayLocalCache flightReplayLocalCache;

    /**
     * 执行 AI 调用请求：开启分布式模式时走集群 single-flight，否则回退本地 single-flight。
     *
     * @param stage AI 调用阶段标识
     * @param requestKey 请求幂等键，用于定位同一 flight
     * @param supplier 实际 AI 调用
     * @return AI 调用结果（可能来自其他节点的结果回放）
     */
    public String execute(String stage, String requestKey, Supplier<String> supplier) {
        // （1）刷新本地 L1 缓存上限，并解析当前运行模式
        flightReplayLocalCache.refreshMaxSize(configuration.getL1CacheMaxSize());
        FlightMode mode = FlightMode.from(configuration.normalizedMode());
        // （2）功能关闭、本地模式或未开启分布式时直接走本地 single-flight
        if (!Boolean.TRUE.equals(configuration.getEnable()) || mode == FlightMode.LOCAL || !Boolean.TRUE.equals(configuration.getDistributedEnabled())) {
            return localSingleFlightService.execute(requestKey, supplier);
        }
        // （3）分布式执行，HYBRID 模式下失败自动降级为本地 single-flight
        try {
            return executeDistributed(stage, requestKey, supplier);
        } catch (RuntimeException ex) {
            if (mode == FlightMode.HYBRID) {
                log.warn("Distributed single-flight fallback to local mode, stage={}, key={}, reason={}", stage, requestKey, ex.getMessage());
                return localSingleFlightService.execute(requestKey, supplier);
            }
            throw ex;
        }
    }

    /**
     * 分布式执行主流程：优先本地回放，其次抢占 owner 或作为 follower 等待结果。
     */
    private String executeDistributed(String stage, String requestKey, Supplier<String> supplier) {
        // （1）归一化 stage 与 requestKey，并解析该阶段的 flight 策略
        String safeStage = StrUtil.blankToDefault(stage, "interview-default");
        String safeRequestKey = StrUtil.blankToDefault(requestKey, safeStage + "|no-key");
        InterviewAiSingleFlightConfiguration.StageFlightPolicy policy = configuration.resolveStagePolicy(safeStage);
        // （2）命中本地 L1 回放缓存时直接返回
        String localReplay = flightReplayLocalCache.get(safeStage, safeRequestKey);
        if (localReplay != null) {
            return localReplay;
        }

        // （3）计算 follower 等待截止时间，最多尝试 3 轮抢占
        long deadline = System.currentTimeMillis() + resolveFollowerMaxWaitMillis();
        int attempts = 0;
        while (attempts < 3) {
            attempts++;
            FlightAcquireResult acquireResult = flightCoordinatorRepository.acquireOrJoin(
                    safeStage,
                    safeRequestKey,
                    nodeId(),
                    extractSessionId(safeRequestKey),
                    policy
            );
            // （4）抢占失败或动作缺失时降级为本地 single-flight
            if (acquireResult == null || acquireResult.getAction() == null) {
                return localSingleFlightService.execute(safeRequestKey, supplier);
            }
            // （5）按脚本返回的动作分别处理：成为 owner、回放结果、回放失败或等待
            switch (acquireResult.getAction()) {
                case OWNER_NEW, OWNER_TAKEOVER -> {
                    return ownerExecute(safeStage, safeRequestKey, acquireResult.getOwnerToken(), supplier, policy);
                }
                case REPLAY_SUCCESS -> {
                    String replay = tryReadSuccessReplay(safeStage, safeRequestKey, policy);
                    if (replay != null) {
                        return replay;
                    }
                }
                case REPLAY_FAILURE -> throw replayFailure(acquireResult);
                case FOLLOWER_WAIT -> {
                    String followerReplay = followerWait(safeStage, safeRequestKey, policy, deadline);
                    if (followerReplay != null) {
                        return followerReplay;
                    }
                }
                default -> {
                    return localSingleFlightService.execute(safeRequestKey, supplier);
                }
            }
        }
        // （6）抢占轮次耗尽，抛出拒绝异常（HYBRID 模式下由上层降级为本地执行）
        throw new CompletionException(new RejectedExecutionException("distributed single-flight max attempts exceeded"));
    }

    /**
     * owner 执行流程：标记运行态、启动心跳、执行 AI 调用并落库终态结果。
     *
     * @param ownerToken 本次抢占获得的 owner 令牌
     * @return AI 调用结果
     */
    private String ownerExecute(String stage, String requestKey, Long ownerToken,
                                Supplier<String> supplier,
                                InterviewAiSingleFlightConfiguration.StageFlightPolicy policy) {
        // （1）先把状态推进为 RUNNING，失败说明 owner 身份已被接管
        long runningTtlMillis = positive(policy.getRunningTtlMillis(), 15000L);
        boolean markedRunning = flightCoordinatorRepository.markRunning(requestKey, nodeId(), ownerToken, runningTtlMillis);
        if (!markedRunning) {
            return followerWait(stage, requestKey, policy, System.currentTimeMillis() + resolveFollowerMaxWaitMillis());
        }

        // （2）构建 owner 上下文并启动心跳续租任务
        FlightOwnerContext ownerContext = FlightOwnerContext.builder()
                .stage(stage)
                .requestKey(requestKey)
                .ownerId(nodeId())
                .ownerToken(ownerToken)
                .policy(policy)
                .build();
        String heartbeatTaskKey = flightHeartbeatManager.start(
                ownerContext,
                () -> flightCoordinatorRepository.heartbeat(requestKey, nodeId(), ownerToken, runningTtlMillis)
        );
        try {
            // （3）执行真实 AI 调用，并序列化结果写入 Redis
            String result = supplier.get();
            FlightStoredResult storedResult = flightResultSerializer.serialize(result, ownerToken, policy);
            long resultTtlMillis = positive(policy.getResultTtlMillis(), 600000L);
            if (!flightCoordinatorRepository.storeResult(requestKey, nodeId(), ownerToken, storedResult, resultTtlMillis)) {
                throw new IllegalStateException("failed to store distributed flight result");
            }
            // （4）把状态置为成功终态；若 owner 身份已失效则尝试直接回放结果
            if (!flightCoordinatorRepository.finishSuccess(requestKey, nodeId(), ownerToken, resultTtlMillis)) {
                String replay = tryReadSuccessReplay(stage, requestKey, policy);
                if (replay != null) {
                    return replay;
                }
                throw new IllegalStateException("failed to finish distributed flight success state");
            }
            // （5）广播成功事件并写入本地 L1 缓存
            flightNotificationService.publish(requestKey, "owner_succeeded", FlightStatus.SUCCEEDED, ownerToken, null, false);
            flightReplayLocalCache.put(stage, requestKey, result, policy);
            return result;
        } catch (Throwable ex) {
            // （6）失败时归类异常，写入失败终态并广播失败事件
            FlightFailure failure = classifyFailure(ex);
            flightCoordinatorRepository.finishFailure(
                    requestKey,
                    nodeId(),
                    ownerToken,
                    failure.errorType,
                    failure.errorCode,
                    failure.retryable,
                    positive(policy.getFailedResultTtlMillis(), 60000L)
            );
            flightNotificationService.publish(requestKey, "owner_failed", FlightStatus.FAILED, ownerToken, failure.errorType, failure.retryable);
            throw rethrow(ex);
        } finally {
            // （7）无论成功失败都停止心跳任务
            flightHeartbeatManager.stop(heartbeatTaskKey);
        }
    }

    /**
     * follower 等待流程：在截止时间前轮询结果并阻塞等待 owner 的终态通知。
     *
     * @param deadlineMillis 等待截止时间戳
     * @return 成功结果，等待超时或未拿到结果时返回 null
     * @throws IllegalStateException owner 已失败且不可重试时抛出
     */
    private String followerWait(String stage, String requestKey,
                                InterviewAiSingleFlightConfiguration.StageFlightPolicy policy,
                                long deadlineMillis) {
        // （1）读取 Stream 阻塞时长与轮询兜底间隔
        long streamBlockTimeoutMillis = positive(configuration.getStreamBlockTimeoutMillis(), 3000L);
        long pollIntervalMillis = positive(configuration.getPollFallbackIntervalMillis(), 2000L);
        long nextPollAt = System.currentTimeMillis();
        // （2）在截止时间前循环等待结果
        while (System.currentTimeMillis() < deadlineMillis) {
            String replay = tryReadSuccessReplay(stage, requestKey, policy);
            if (replay != null) {
                return replay;
            }
            // （3）owner 已失败且不可重试时直接抛错，避免无谓等待
            FlightMetaSnapshot metaSnapshot = flightCoordinatorRepository.getMeta(requestKey);
            if (metaSnapshot != null && metaSnapshot.getStatus() == FlightStatus.FAILED && !Boolean.TRUE.equals(metaSnapshot.getRetryable())) {
                throw new IllegalStateException("distributed single-flight previous failure: "
                        + (metaSnapshot.getErrorCode() == null ? "FAILED" : metaSnapshot.getErrorCode()));
            }
            // （4）剩余时间不足时结束等待
            long remainingMillis = deadlineMillis - System.currentTimeMillis();
            if (remainingMillis <= 0) {
                return null;
            }
            // （5）阻塞等待终态事件，并按间隔做一次结果轮询兜底
            flightNotificationService.waitForTerminalEvent(requestKey, Math.min(streamBlockTimeoutMillis, remainingMillis));
            if (System.currentTimeMillis() >= nextPollAt) {
                String polledReplay = tryReadSuccessReplay(stage, requestKey, policy);
                if (polledReplay != null) {
                    return polledReplay;
                }
                nextPollAt = System.currentTimeMillis() + pollIntervalMillis;
            }
        }
        return null;
    }

    /**
     * 尝试读取成功结果：先查本地 L1 缓存，再按元数据状态从 Redis 回放并回填缓存。
     *
     * @return 回放结果，尚未成功或无结果时返回 null
     */
    private String tryReadSuccessReplay(String stage, String requestKey,
                                        InterviewAiSingleFlightConfiguration.StageFlightPolicy policy) {
        // （1）优先命中本地 L1 缓存
        String localReplay = flightReplayLocalCache.get(stage, requestKey);
        if (localReplay != null) {
            return localReplay;
        }
        // （2）元数据未处于成功态时不回放
        FlightMetaSnapshot metaSnapshot = flightCoordinatorRepository.getMeta(requestKey);
        if (metaSnapshot == null || metaSnapshot.getStatus() != FlightStatus.SUCCEEDED) {
            return null;
        }
        // （3）读取结果并反序列化，同时回填本地缓存
        FlightStoredResult storedResult = flightCoordinatorRepository.getStoredResult(requestKey);
        if (storedResult == null) {
            return null;
        }
        String replay = flightResultSerializer.deserialize(storedResult);
        flightReplayLocalCache.put(stage, requestKey, replay, policy);
        return replay;
    }

    /**
     * 把失败回放结果转换为运行时异常：可重试时包装为拒绝异常，否则为状态异常。
     */
    private RuntimeException replayFailure(FlightAcquireResult acquireResult) {
        // （1）拼装失败信息，附带错误码
        boolean retryable = Boolean.TRUE.equals(acquireResult.getRetryable());
        String message = "distributed single-flight replay failure";
        if (StrUtil.isNotBlank(acquireResult.getErrorCode())) {
            message = message + ": " + acquireResult.getErrorCode();
        }
        // （2）按是否可重试选择异常类型
        if (retryable) {
            return new CompletionException(new RejectedExecutionException(message));
        }
        return new IllegalStateException(message);
    }

    /**
     * 原样抛出运行时异常，受检异常统一包装为 CompletionException。
     */
    private RuntimeException rethrow(Throwable throwable) {
        if (throwable instanceof RuntimeException runtimeException) {
            return runtimeException;
        }
        return new CompletionException(throwable);
    }

    /**
     * 将调用异常归类为 flight 失败类型，并决定是否允许其他节点接管重试。
     */
    private FlightFailure classifyFailure(Throwable throwable) {
        // （1）剥开 CompletionException 取真实根因
        Throwable cause = throwable;
        while (cause instanceof CompletionException && cause.getCause() != null) {
            cause = cause.getCause();
        }
        // （2）保护异常按错误码映射失败类型
        if (cause instanceof InterviewAiGuardException guardException) {
            return switch (guardException.getErrorCode()) {
                case AI_TIMEOUT -> new FlightFailure(FlightErrorType.TIMEOUT, guardException.getErrorCode().name(), true);
                case AI_OVERLOADED -> new FlightFailure(FlightErrorType.OVERLOAD, guardException.getErrorCode().name(), true);
                case AI_UNAVAILABLE -> new FlightFailure(FlightErrorType.PROVIDER, guardException.getErrorCode().name(), true);
            };
        }
        // （3）其他异常按类型映射：超时/拒绝可重试，参数与未知错误不可重试
        if (cause instanceof TimeoutException) {
            return new FlightFailure(FlightErrorType.TIMEOUT, "TIMEOUT", true);
        }
        if (cause instanceof RejectedExecutionException) {
            return new FlightFailure(FlightErrorType.OVERLOAD, "OVERLOADED", true);
        }
        if (cause instanceof IllegalArgumentException) {
            return new FlightFailure(FlightErrorType.VALIDATION, "VALIDATION", false);
        }
        return new FlightFailure(FlightErrorType.UNEXPECTED, "UNEXPECTED", false);
    }

    /** 解析 follower 最长等待时长，未配置或非法时默认 20 秒。 */
    private long resolveFollowerMaxWaitMillis() {
        return positive(configuration.getFollowerMaxWaitMillis(), 20000L);
    }

    private long positive(Long value, long defaultValue) {
        return value != null && value > 0 ? value : defaultValue;
    }

    /**
     * 从 requestKey 中按 "|" 分隔取第二段作为会话 id。
     */
    private String extractSessionId(String requestKey) {
        // （1）requestKey 为空时返回 null
        if (StrUtil.isBlank(requestKey)) {
            return null;
        }
        // （2）取第二段作为 sessionId，不足两段时返回 null
        String[] parts = requestKey.split("\\|");
        return parts.length > 1 ? parts[1] : null;
    }

    private String nodeId() {
        return Holder.NODE_ID;
    }

    /**
     * 懒加载当前节点标识的内部工具类，用于生成 owner 节点身份。
     */
    private static final class Holder {
        private static final String NODE_ID = resolveNodeId();

        /**
         * 生成当前节点标识：主机名 + JVM 进程名，主机名不可用时退化为进程名。
         */
        private static String resolveNodeId() {
            // （1）优先使用「主机名@进程名」，解析失败时仅用进程名
            try {
                return InetAddress.getLocalHost().getHostName() + "@" + ManagementFactory.getRuntimeMXBean().getName();
            } catch (UnknownHostException ex) {
                return ManagementFactory.getRuntimeMXBean().getName();
            }
        }
    }

    /**
     * 分布式协调过程中对异常进行归类后的内部失败对象，
     * 用于统一写入失败状态并决定是否允许重试接管。
     */
    private record FlightFailure(FlightErrorType errorType, String errorCode, boolean retryable) {
    }
}
