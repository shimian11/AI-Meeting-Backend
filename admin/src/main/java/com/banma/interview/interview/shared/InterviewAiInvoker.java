package com.banma.interview.interview.shared;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.banma.interview.agent.dao.entity.AgentPropertiesDO;
import com.banma.interview.interview.application.guard.core.AiCallGuardService;
import com.banma.interview.interview.application.guard.core.InterviewAiGuardStage;
import com.banma.interview.interview.application.guard.singleflight.service.DistributedInterviewAiSingleFlightService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * 面试 AI 的统一调用入口，负责生成请求指纹、串联限流熔断保护、
 * 分布式 single-flight 复用以及最终的模型调用过程。
 */
@Component
@RequiredArgsConstructor
public class InterviewAiInvoker {

    /** AI 调用治理（Resilience4j 装饰器链） */
    private final AiCallGuardService aiCallGuardService;
    /** 分布式 single-flight（Redis 协调 + 回放） */
    private final DistributedInterviewAiSingleFlightService distributedInterviewAiSingleFlightService;


    /**
     * 以自定义调用体走守卫链（single-flight + 熔断 + 按 stage 超时）。
     *
     * @param stage           阶段名，决定超时与熔断参数
     * @param singleFlightKey 幂等键，同键并发只执行一次
     * @param task            实际调用体
     * @return 调用体返回的 JSON 字符串
     */
    public String callAiSyncWithCallable(String stage, String singleFlightKey, Callable<String> task) throws Exception {
        // （1）委托给内部守卫链，本方法只做对外契约与文档
        return guardedCall(stage, singleFlightKey, task);
    }


    /**
     * 构建包含阶段、会话、题号与回答哈希的 single-flight 键。
     *
     * @param stage 调用阶段
     * @param sessionId 会话 id
     * @param questionNumber 题号
     * @param answerContent 回答内容，取 SHA-256 前 16 位作为摘要
     * @return single-flight 键
     */
    public String buildSingleFlightKey(
            String stage,
            String sessionId,
            String questionNumber,
            String answerContent) {
        // （1）各段空值使用固定占位符，保证键稳定
        String safeStage = StrUtil.blankToDefault(stage, "interview-default");
        String safeSessionId = StrUtil.blankToDefault(StrUtil.trimToEmpty(sessionId), "no-session");
        String safeQuestionNumber = StrUtil.blankToDefault(StrUtil.trimToEmpty(questionNumber), "-");
        // （2）回答内容取哈希摘要，避免键过长或包含敏感原文
        String safeAnswerHash = StrUtil.isBlank(answerContent)
                ? "-"
                : DigestUtil.sha256Hex(answerContent.trim()).substring(0, 16);
        return safeStage + "|" + safeSessionId + "|" + safeQuestionNumber + "|" + safeAnswerHash;
    }

    /**
     * 构建包含阶段、会话与业务键的 single-flight 键。
     */
    public String buildSingleFlightKey(String stage, String sessionId, String businessKey) {
        // （1）各段空值使用固定占位符后拼接
        String safeStage = StrUtil.blankToDefault(stage, "interview-default");
        String safeSessionId = StrUtil.blankToDefault(StrUtil.trimToEmpty(sessionId), "no-session");
        String safeBusinessKey = StrUtil.blankToDefault(StrUtil.trimToEmpty(businessKey), "-");
        return safeStage + "|" + safeSessionId + "|" + safeBusinessKey;
    }

    /**
     * 在 single-flight 与限流熔断保护下执行 AI 调用。
     *
     * @param stage 调用阶段
     * @param singleFlightKey single-flight 键
     * @param callable 实际模型调用
     * @return 模型返回内容
     */
    private String guardedCall(String stage, String singleFlightKey, Callable<String> callable) throws Exception {
        // （1）兜底阶段名与键，避免空值影响保护逻辑
        String safeStage = StrUtil.blankToDefault(stage, "interview-default");
        String key = StrUtil.blankToDefault(singleFlightKey, safeStage + "|no-key");
        // （2）外层 single-flight 复用结果，内层执行限流熔断保护
        return distributedInterviewAiSingleFlightService.execute(
                safeStage,
                key,
                () -> aiCallGuardService.execute(safeStage, key, callable)
        );
    }
}