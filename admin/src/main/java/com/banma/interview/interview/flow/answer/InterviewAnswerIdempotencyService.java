package com.banma.interview.interview.flow.answer;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.banma.interview.interview.api.io.resp.InterviewAnswerRespDTO;
import com.banma.interview.interview.config.InterviewAnswerGuardConfiguration;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Idempotency state manager for interview answer requests.
 * 面试答题请求的幂等状态管理：维护处理中占位与成功结果回放。
 */
@Service
@RequiredArgsConstructor
public class InterviewAnswerIdempotencyService {

    private static final String PROCESSING_KEY_PREFIX = "interview:answer:idempotency:processing:";
    private static final String REPLAY_KEY_PREFIX = "interview:answer:idempotency:replay:";
    // PROCESSING_KEY_PREFIX 为处理中占位 key 前缀，REPLAY_KEY_PREFIX 为成功结果回放 key 前缀

    /** Redis 模板，用于缓存读写 */
    private final StringRedisTemplate stringRedisTemplate;
    /** 答题防抖与限流配置 */
    private final InterviewAnswerGuardConfiguration configuration;

    /**
     * 尝试开启一次答题请求：命中成功回放则直接返回历史结果，否则用 Redis 占位区分新请求与处理中。
     */
    public TryStartResult tryStart(String sessionId, String requestId) {
        // （1）参数不完整时不做幂等控制，直接按新请求放行
        if (StrUtil.isBlank(sessionId) || StrUtil.isBlank(requestId)) {
            return TryStartResult.newRequest();
        }
        // （2）优先读取成功回放，命中则无需重复执行主流程
        String replayPayload = stringRedisTemplate.opsForValue().get(replayKey(sessionId, requestId));
        if (StrUtil.isNotBlank(replayPayload)) {
            InterviewAnswerRespDTO replayResponse = safeParseReplay(replayPayload);
            if (replayResponse != null) {
                return TryStartResult.succeeded(replayResponse);
            }
        }

        // （3）用 setIfAbsent 抢占处理中占位：抢到即新请求，未抢到说明该请求仍在处理
        Boolean started = stringRedisTemplate.opsForValue().setIfAbsent(
                processingKey(sessionId, requestId),
                "1",
                resolveProcessingExpireSeconds(),
                TimeUnit.SECONDS
        );
        if (Boolean.TRUE.equals(started)) {
            return TryStartResult.newRequest();
        }
        return TryStartResult.processing();
    }

    /**
     * 标记请求已成功：写入响应快照供后续回放，并删除处理中占位。
     */
    public void markSucceeded(String sessionId, String requestId, InterviewAnswerRespDTO response) {
        // （1）关键参数缺失时直接跳过
        if (StrUtil.isBlank(sessionId) || StrUtil.isBlank(requestId) || response == null) {
            return;
        }
        // （2）序列化响应写入回放 key，并释放处理中占位
        String replayPayload = JSON.toJSONString(response);
        stringRedisTemplate.opsForValue().set(
                replayKey(sessionId, requestId),
                replayPayload,
                resolveReplayExpireHours(),
                TimeUnit.HOURS
        );
        stringRedisTemplate.delete(processingKey(sessionId, requestId));
    }

    /**
     * 清除处理中占位，供失败或未提交成功的路径释放幂等门禁。
     */
    public void clearProcessing(String sessionId, String requestId) {
        // （1）参数校验后删除处理中占位
        if (StrUtil.isBlank(sessionId) || StrUtil.isBlank(requestId)) {
            return;
        }
        stringRedisTemplate.delete(processingKey(sessionId, requestId));
    }

    /**
     * 安全解析回放 JSON，解析异常时返回 null，避免脏数据阻断主流程。
     */
    private InterviewAnswerRespDTO safeParseReplay(String payload) {
        // （1）尝试反序列化，失败按无回放处理
        try {
            return JSON.parseObject(payload, InterviewAnswerRespDTO.class);
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * 拼接处理中占位使用的 Redis key。
     */
    private String processingKey(String sessionId, String requestId) {
        return PROCESSING_KEY_PREFIX + sessionId + ":" + requestId;
    }

    /**
     * 拼接成功回放使用的 Redis key。
     */
    private String replayKey(String sessionId, String requestId) {
        return REPLAY_KEY_PREFIX + sessionId + ":" + requestId;
    }

    /**
     * 解析处理中占位的过期秒数，取基础值与长尾值的较大者。
     */
    private long resolveProcessingExpireSeconds() {
        // （1）未配置或配置非法时使用默认值兜底
        Long configured = configuration.getProcessingExpireSeconds();
        long base = configured == null || configured <= 0 ? 120L : configured;
        Long longTail = configuration.getProcessingLongTailExpireSeconds();
        long guard = longTail == null || longTail <= 0 ? base : longTail;
        return Math.max(base, guard);
    }

    /**
     * 解析成功回放的过期小时数，未配置时默认 24 小时。
     */
    private long resolveReplayExpireHours() {
        Long configured = configuration.getReplayExpireHours();
        return configured == null || configured <= 0 ? 24L : configured;
    }

    /**
     * 幂等门禁的判定结果：新请求、处理中或已成功并回放。
     */
    @Getter
    public static final class TryStartResult {
        /** 幂等门禁状态（NEW/PROCESSING/SUCCEEDED） */
        private final TryStartStatus status;
        /** 幂等命中时回放的历史响应 */
        private final InterviewAnswerRespDTO replayResponse;

        private TryStartResult(TryStartStatus status, InterviewAnswerRespDTO replayResponse) {
            this.status = status;
            this.replayResponse = replayResponse;
        }

        public static TryStartResult newRequest() {
            return new TryStartResult(TryStartStatus.NEW, null);
        }

        public static TryStartResult processing() {
            return new TryStartResult(TryStartStatus.PROCESSING, null);
        }

        public static TryStartResult succeeded(InterviewAnswerRespDTO replayResponse) {
            return new TryStartResult(TryStartStatus.SUCCEEDED, replayResponse);
        }
    }

    public enum TryStartStatus {
        NEW,                            // 新请求
        PROCESSING,                     // 该请求正在被处理
        SUCCEEDED                       // 该请求之前已成功处理过
    }
}
