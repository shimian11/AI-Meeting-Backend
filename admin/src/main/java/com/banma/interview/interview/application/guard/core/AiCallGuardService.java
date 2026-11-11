package com.banma.interview.interview.application.guard.core;

import cn.hutool.core.util.StrUtil;
import com.banma.interview.interview.config.InterviewAiGuardConfiguration;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.ratelimiter.RequestNotPermitted;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.timelimiter.TimeLimiter;
import io.github.resilience4j.timelimiter.TimeLimiterConfig;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Unified AI guard service with timeout/circuit-breaker/bulkhead/retry.
 * 统一的 AI 调用保护服务，按 stage 组合熔断、并发隔离、重试与超时能力。
 */
@Service
@Slf4j
public class AiCallGuardService {

    /** AI 治理配置（熔断/舱壁/重试/超时） */
    private final InterviewAiGuardConfiguration configuration;
    /** Micrometer 指标注册表 */
    private final MeterRegistry meterRegistry;
    /** 专用线程池 */
    private final ExecutorService aiIoExecutor;

    private final ConcurrentMap<String, CircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Bulkhead> bulkheads = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, Retry> retries = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, TimeLimiter> timeLimiters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, AtomicInteger> circuitStateGauges = new ConcurrentHashMap<>();

    public AiCallGuardService(
            InterviewAiGuardConfiguration configuration,
            MeterRegistry meterRegistry,
            @Qualifier("interviewAiIoExecutor") ExecutorService aiIoExecutor) {
        this.configuration = configuration;
        this.meterRegistry = meterRegistry;
        this.aiIoExecutor = aiIoExecutor;
    }

    /**
     * 执行受保护的 AI 调用：按阶段走熔断/限流/重试/超时链路，并统一记录指标与异常语义。
     *
     * @param stage AI 调用阶段标识，用于隔离各阶段的保护组件
     * @param requestKey 请求标识，仅用于日志排查
     * @param action 实际 AI 调用动作
     * @return 调用结果
     * @throws InterviewAiGuardException 调用失败时按超时/过载/不可用归类后抛出
     */
    public <T> T execute(String stage, String requestKey, Callable<T> action) {
        // （1）归一化 stage，保证保护组件按阶段稳定复用
        String safeStage = StrUtil.isNotBlank(stage) ? stage : "interview-default";
        long startNano = System.nanoTime();
        try {
            // （2）开关开启时走保护链路，否则直接执行
            T result;
            if (Boolean.TRUE.equals(configuration.getEnable())) {
                result = guardedExecute(safeStage, action);
            } else {
                result = action.call();
            }
            // （3）成功调用记录指标并返回
            recordMetrics(safeStage, "success", System.nanoTime() - startNano);
            return result;
        } catch (Throwable ex) {
            // （4）异常归类为统一语义，补充指标与过载拒绝计数
            InterviewAiGuardException guardException = classifyException(ex, safeStage);
            String resultTag = guardException.getErrorCode().name().toLowerCase();
            recordMetrics(safeStage, resultTag, System.nanoTime() - startNano);
            if (guardException.getErrorCode() == InterviewAiGuardErrorCode.AI_OVERLOADED
                    && isBulkheadReject(unwrap(ex))) {
                meterRegistry.counter("ai_bulkhead_rejected_total", "stage", safeStage).increment();
            }
            // （5）记录告警日志后抛出归类后的异常
            log.warn(
                    "AI guarded call failed, stage={}, requestKey={}, code={}, message={}",
                    safeStage,
                    requestKey,
                    guardException.getErrorCode(),
                    guardException.getMessage()
            );
            throw guardException;
        }
    }

    /**
     * 按固定顺序装饰保护组件后执行调用。
     */
    private <T> T guardedExecute(String stage, Callable<T> action) throws Exception {
        // （1）按 stage 惰性获取（并缓存）四类保护组件
        CircuitBreaker circuitBreaker = circuitBreakers.computeIfAbsent(stage, this::newCircuitBreaker);
        Bulkhead bulkhead = bulkheads.computeIfAbsent(stage, this::newBulkhead);
        Retry retry = retries.computeIfAbsent(stage, this::newRetry);

        // 装饰顺序固定为：CircuitBreaker -> Bulkhead -> Retry -> TimeLimiter。
        // 这样既能在入口快速拒绝故障流量，也能把重试限制在限流与超时边界内。
        // （2）按上述顺序包装调用并执行
        Callable<T> decorated = CircuitBreaker.decorateCallable(
                circuitBreaker,
                Bulkhead.decorateCallable(
                        bulkhead,
                        Retry.decorateCallable(
                                retry,
                                () -> callWithTimeLimiter(stage, action)
                        )
                )
        );
        return decorated.call();
    }

    /**
     * 在隔离线程池中异步执行调用，并由 TimeLimiter 统一裁剪超时。
     */
    private <T> T callWithTimeLimiter(String stage, Callable<T> action) throws Exception {
        // （1）按 stage 获取超时组件
        TimeLimiter timeLimiter = timeLimiters.computeIfAbsent(stage, this::newTimeLimiter);
        // AI I/O 调用放到专用线程池，避免占用业务线程，并由 TimeLimiter 统一裁剪超时。
        // （2）包装为带超时的异步调用并执行
        Callable<T> timeLimitedCall = TimeLimiter.decorateFutureSupplier(
                timeLimiter,
                () -> CompletableFuture.supplyAsync(() -> {
                    try {
                        return action.call();
                    } catch (Exception ex) {
                        throw new CompletionException(ex);
                    }
                }, aiIoExecutor)
        );
        return timeLimitedCall.call();
    }

    /**
     * 为指定阶段创建熔断器，并把状态映射为可观测的 Gauge 指标。
     */
    private CircuitBreaker newCircuitBreaker(String stage) {
        // （1）按配置（含默认值）构建熔断器参数
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .failureRateThreshold(resolveFailureRateThreshold())
                .slidingWindowSize(resolveSlidingWindowSize())
                .permittedNumberOfCallsInHalfOpenState(resolveHalfOpenProbeCount())
                .waitDurationInOpenState(Duration.ofMillis(resolveOpenWaitMillis()))
                .minimumNumberOfCalls(Math.min(10, resolveSlidingWindowSize()))
                .build();
        // （2）创建熔断器并注册状态 Gauge
        CircuitBreaker breaker = CircuitBreaker.of(stage, config);
        AtomicInteger stateGauge = new AtomicInteger(mapCircuitState(breaker.getState()));
        AtomicInteger existing = circuitStateGauges.putIfAbsent(stage, stateGauge);
        AtomicInteger gaugeTarget = existing != null ? existing : stateGauge;
        if (existing == null) {
            meterRegistry.gauge("ai_circuit_state", Tags.of("stage", stage), gaugeTarget, AtomicInteger::get);
        }
        // （3）订阅状态迁移事件，同步刷新 Gauge 数值
        breaker.getEventPublisher().onStateTransition(event ->
                gaugeTarget.set(mapCircuitState(event.getStateTransition().getToState())));
        return breaker;
    }

    /**
     * 为指定阶段创建并发隔离（Bulkhead），最大并发取阶段策略或默认 20。
     */
    private Bulkhead newBulkhead(String stage) {
        // （1）解析阶段策略中的最大并发数
        InterviewAiGuardConfiguration.StagePolicy policy = configuration.resolveStagePolicy(stage);
        int maxConcurrent = policy.getMaxConcurrentCalls() != null && policy.getMaxConcurrentCalls() > 0
                ? policy.getMaxConcurrentCalls()
                : 20;
        // （2）不等待直接拒绝超额请求，避免调用方线程堆积
        BulkheadConfig config = BulkheadConfig.custom()
                .maxConcurrentCalls(maxConcurrent)
                .maxWaitDuration(Duration.ZERO)
                .build();
        return Bulkhead.of(stage, config);
    }

    /**
     * 为指定阶段创建重试组件，仅对超时与 IO 异常重试，其余异常直接透出。
     */
    private Retry newRetry(String stage) {
        // （1）解析阶段策略中的重试次数与间隔
        InterviewAiGuardConfiguration.StagePolicy policy = configuration.resolveStagePolicy(stage);
        int retryCount = policy.getRetryCount() != null && policy.getRetryCount() >= 0 ? policy.getRetryCount() : 0;
        long waitMillis = policy.getRetryWaitMillis() != null && policy.getRetryWaitMillis() >= 0
                ? policy.getRetryWaitMillis()
                : 0L;
        // （2）配置最大尝试次数与可重试异常
        RetryConfig config = RetryConfig.custom()
                .maxAttempts(Math.max(1, retryCount + 1))
                .waitDuration(Duration.ofMillis(waitMillis))
                .retryOnException(this::isRetriable)
                .ignoreExceptions(CallNotPermittedException.class, BulkheadFullException.class, InterviewAiGuardException.class)
                .build();
        return Retry.of(stage, config);
    }

    /**
     * 为指定阶段创建超时组件，超时时间取阶段策略或默认 5 秒。
     */
    private TimeLimiter newTimeLimiter(String stage) {
        // （1）解析阶段策略中的超时时间
        InterviewAiGuardConfiguration.StagePolicy policy = configuration.resolveStagePolicy(stage);
        long timeoutMillis = policy.getTimeoutMillis() != null && policy.getTimeoutMillis() > 0
                ? policy.getTimeoutMillis()
                : 5000L;
        // （2）超时后取消正在执行的 Future，避免线程长期占用
        TimeLimiterConfig config = TimeLimiterConfig.custom()
                .timeoutDuration(Duration.ofMillis(timeoutMillis))
                .cancelRunningFuture(true)
                .build();
        return TimeLimiter.of(stage, config);
    }

    /** 判断异常是否可重试：仅超时与 IO 异常可重试。 */
    private boolean isRetriable(Throwable throwable) {
        Throwable cause = unwrap(throwable);
        return cause instanceof TimeoutException || cause instanceof IOException;
    }

    /**
     * 将底层异常归类为超时、过载、不可用三类统一语义。
     */
    private InterviewAiGuardException classifyException(Throwable throwable, String stage) {
        // （1）先解包并复用已有的保护异常
        Throwable cause = unwrap(throwable);
        if (cause instanceof InterviewAiGuardException guardException) {
            return guardException;
        }

        // 统一映射为前端可稳定识别的三类语义，减少各链路各自兜底导致的分支复杂度。
        // （2）按异常类型映射错误码与提示信息
        InterviewAiGuardErrorCode code;
        String message;
        if (cause instanceof TimeoutException) {
            code = InterviewAiGuardErrorCode.AI_TIMEOUT;
            message = "AI_TIMEOUT: ai call timed out, please retry";
        } else if (isOverloaded(cause)) {
            code = InterviewAiGuardErrorCode.AI_OVERLOADED;
            message = "AI_OVERLOADED: ai service is busy, please retry";
        } else {
            code = InterviewAiGuardErrorCode.AI_UNAVAILABLE;
            message = "AI_UNAVAILABLE: ai service is unavailable, please retry";
        }
        return new InterviewAiGuardException(code, stage, message, cause);
    }

    /** 判断异常是否属于过载类（熔断拒绝、限流拒绝、线程池拒绝）。 */
    private boolean isOverloaded(Throwable cause) {
        return cause instanceof BulkheadFullException
                || cause instanceof RequestNotPermitted
                || cause instanceof RejectedExecutionException;
    }

    /** 判断异常是否由并发隔离或线程池拒绝导致。 */
    private boolean isBulkheadReject(Throwable cause) {
        return cause instanceof BulkheadFullException || cause instanceof RejectedExecutionException;
    }

    /**
     * 逐层剥开 CompletionException / ExecutionException，取到真实根因。
     */
    private Throwable unwrap(Throwable throwable) {
        // （1）循环下钻包装异常的 cause，直至不再可解包
        Throwable current = throwable;
        while (current instanceof CompletionException || current instanceof ExecutionException) {
            if (current.getCause() == null) {
                break;
            }
            current = current.getCause();
        }
        return current;
    }

    /**
     * 记录调用次数与耗时指标，按 stage 与结果打标签。
     */
    private void recordMetrics(String stage, String result, long elapsedNano) {
        // （1）累加调用计数并记录本次耗时
        meterRegistry.counter("ai_call_total", "stage", stage, "result", result).increment();
        meterRegistry.timer("ai_call_latency_ms", "stage", stage, "result", result)
                .record(Duration.ofNanos(elapsedNano));
    }

    /**
     * 将熔断器状态映射为 Gauge 数值：0 关闭、1 半开、2 打开。
     */
    private int mapCircuitState(CircuitBreaker.State state) {
        // （1）关闭为 0，半开为 1，其余（打开/强制打开等）统一为 2
        if (state == CircuitBreaker.State.CLOSED) {
            return 0;
        }
        if (state == CircuitBreaker.State.HALF_OPEN) {
            return 1;
        }
        return 2;
    }

    /** 解析熔断失败率阈值，未配置或非法时默认 50%。 */
    private float resolveFailureRateThreshold() {
        Float configured = configuration.getCircuitFailureRateThreshold();
        return configured != null && configured > 0 ? configured : 50F;
    }

    /** 解析熔断滑动窗口大小，未配置或非法时默认 50。 */
    private int resolveSlidingWindowSize() {
        Integer configured = configuration.getCircuitSlidingWindowSize();
        return configured != null && configured > 0 ? configured : 50;
    }

    /** 解析半开状态允许的探针请求数，未配置或非法时默认 10。 */
    private int resolveHalfOpenProbeCount() {
        Integer configured = configuration.getCircuitPermittedCallsInHalfOpenState();
        return configured != null && configured > 0 ? configured : 10;
    }

    /** 解析熔断打开后的等待时长（毫秒），未配置或非法时默认 30 秒。 */
    private long resolveOpenWaitMillis() {
        Long configured = configuration.getCircuitOpenStateWaitMillis();
        return configured != null && configured > 0 ? configured : 30000L;
    }
}
