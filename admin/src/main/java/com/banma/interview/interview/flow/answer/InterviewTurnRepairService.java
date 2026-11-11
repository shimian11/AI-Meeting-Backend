package com.banma.interview.interview.flow.answer;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.banma.interview.interview.config.InterviewTurnRepairConfiguration;
import com.banma.interview.interview.service.InterviewQuestionCacheService;
import com.banma.interview.interview.service.model.InterviewTurnLog;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Compensates failed turn persistence asynchronously.
 * 轮次落库失败的异步补偿：失败任务入队后由定时任务重试补齐。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InterviewTurnRepairService {

    private static final String TURN_REPAIR_QUEUE_KEY = "interview:turn:repair:queue";

    /** Redis 模板，用于缓存读写 */
    private final StringRedisTemplate stringRedisTemplate;
    /** 题目、流程与得分的 Redis 缓存读写 */
    private final InterviewQuestionCacheService interviewQuestionCacheService;
    /** 轮次补偿队列配置 */
    private final InterviewTurnRepairConfiguration configuration;
    /** Micrometer 指标注册表 */
    private final MeterRegistry meterRegistry;

    /**
     * 把失败的轮次落库任务写入补偿队列，附带原因便于排障。
     */
    public void enqueue(String sessionId, InterviewTurnLog turnLog, String reason) {
        // （1）关键参数缺失时直接跳过
        if (StrUtil.isBlank(sessionId) || turnLog == null) {
            return;
        }
        try {
            // （2）组装任务写入队列并设置整体过期时间
            TurnRepairTask task = new TurnRepairTask();
            task.setSessionId(sessionId);
            task.setRetryCount(0);
            task.setReason(StrUtil.blankToDefault(reason, "unknown"));
            task.setTurn(turnLog);
            stringRedisTemplate.opsForList().rightPush(TURN_REPAIR_QUEUE_KEY, JSON.toJSONString(task));
            stringRedisTemplate.expire(TURN_REPAIR_QUEUE_KEY, 24, TimeUnit.HOURS);
            meterRegistry.counter("turn_repair_enqueue_total").increment();
        } catch (Exception ex) {
            meterRegistry.counter("turn_repair_enqueue_fail_total").increment();
            log.warn("Failed to enqueue turn repair task, sessionId={}, requestId={}", sessionId, turnLog.getRequestId(), ex);
        }
    }

    /**
     * 定时消费补偿队列：批量出队重试补写轮次，超过最大重试次数则丢弃并告警。
     */
    @Scheduled(fixedDelayString = "${banma-interview.interview.turn-repair.fixed-delay-millis:3000}")
    public void repairPendingTurns() {
        // （1）功能开关关闭时跳过本轮补偿
        if (!Boolean.TRUE.equals(configuration.getEnable())) {
            return;
        }
        // （2）按配置批量出队，逐个解析任务
        int batchSize = resolveBatchSize();
        for (int i = 0; i < batchSize; i++) {
            String payload = stringRedisTemplate.opsForList().leftPop(TURN_REPAIR_QUEUE_KEY);
            if (StrUtil.isBlank(payload)) {
                return;
            }
            TurnRepairTask task = parseTask(payload);
            if (task == null || StrUtil.isBlank(task.getSessionId()) || task.getTurn() == null) {
                meterRegistry.counter("turn_repair_parse_fail_total").increment();
                continue;
            }
            boolean repaired = interviewQuestionCacheService.appendInterviewTurnIfAbsent(task.getSessionId(), task.getTurn());
            // （3）补写成功即结束该任务
            if (repaired) {
                meterRegistry.counter("turn_repair_success_total").increment();
                continue;
            }
            int nextRetry = (task.getRetryCount() == null ? 0 : task.getRetryCount()) + 1;
            // （4）超过最大重试次数则丢弃，否则重新入队等待下一轮
            if (nextRetry > resolveMaxRetries()) {
                meterRegistry.counter("turn_repair_drop_total").increment();
                log.warn("Drop turn repair task after max retries, sessionId={}, requestId={}",
                        task.getSessionId(),
                        task.getTurn() == null ? null : task.getTurn().getRequestId());
                continue;
            }
            task.setRetryCount(nextRetry);
            stringRedisTemplate.opsForList().rightPush(TURN_REPAIR_QUEUE_KEY, JSON.toJSONString(task));
            meterRegistry.counter("turn_repair_retry_total").increment();
        }
    }

    /**
     * 解析队列中的补偿任务 JSON，解析失败返回 null。
     */
    private TurnRepairTask parseTask(String payload) {
        try {
            return JSON.parseObject(payload, TurnRepairTask.class);
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * 解析单轮补偿的批量大小，未配置时默认 50。
     */
    private int resolveBatchSize() {
        Integer configured = configuration.getBatchSize();
        return configured != null && configured > 0 ? configured : 50;
    }

    /**
     * 解析最大重试次数，未配置时默认 6 次。
     */
    private int resolveMaxRetries() {
        Integer configured = configuration.getMaxRetries();
        return configured != null && configured > 0 ? configured : 6;
    }

    /**
     * 轮次补偿任务：会话 ID、待补写轮次、已重试次数与入队原因。
     */
    @Data
    public static class TurnRepairTask {
        /** 面试会话 ID */
        private String sessionId;
        /** 待补写的轮次日志 */
        private InterviewTurnLog turn;
        /** 已重试次数 */
        private Integer retryCount;
        /** 入队原因 */
        private String reason;
    }
}
