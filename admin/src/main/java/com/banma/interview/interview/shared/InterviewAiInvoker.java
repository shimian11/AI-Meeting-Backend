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
     * 同步调用面试评估模型，自动生成 single-flight 键。
     *
     * @param prompt 提示词
     * @param sessionId 会话 id
     * @param agentProperties 智能体配置
     * @return 模型返回内容
     */
    public String callAiSync(String prompt, String sessionId, AgentPropertiesDO agentProperties) throws Exception {
        // （1）按评估阶段与提示内容生成 single-flight 键后发起调用
        String key = buildSingleFlightKey(InterviewAiGuardStage.INTERVIEW_EVALUATION, sessionId, null, prompt);
        return callAiSync(prompt, sessionId, agentProperties, InterviewAiGuardStage.INTERVIEW_EVALUATION, key);
    }

    /**
     * 按指定阶段与 single-flight 键同步调用面试评估模型。
     *
     * @param prompt 提示词
     * @param sessionId 会话 id
     * @param agentProperties 智能体配置
     * @param stage 调用阶段
     * @param singleFlightKey single-flight 键
     * @return 模型返回内容
     */
    public String callAiSync(
            String prompt,
            String sessionId,
            AgentPropertiesDO agentProperties,
            String stage,
            String singleFlightKey) throws Exception {
        // （1）经过限流熔断与 single-flight 保护后执行模型调用
        return guardedCall(stage, singleFlightKey, () -> doChat(prompt, sessionId, agentProperties, null, null));
    }

    /**
     * 携带文件的同步调用（用于仪态评估），自动生成 single-flight 键。
     *
     * @param prompt 提示词
     * @param sessionId 会话 id
     * @param agentProperties 智能体配置
     * @param fileUrl 待评估文件地址
     * @return 模型返回内容
     */
    public String callAiSyncWithFile(
            String prompt,
            String sessionId,
            AgentPropertiesDO agentProperties,
            String fileUrl) throws Exception {
        // （1）按仪态阶段与文件地址生成 single-flight 键后发起调用
        String key = buildSingleFlightKey(InterviewAiGuardStage.INTERVIEW_DEMEANOR, sessionId, fileUrl);
        return callAiSyncWithFile(prompt, sessionId, agentProperties, fileUrl, InterviewAiGuardStage.INTERVIEW_DEMEANOR, key);
    }

    /**
     * 按指定阶段与 single-flight 键携带文件同步调用模型。
     *
     * @param prompt 提示词
     * @param sessionId 会话 id
     * @param agentProperties 智能体配置
     * @param fileUrl 待评估文件地址
     * @param stage 调用阶段
     * @param singleFlightKey single-flight 键
     * @return 模型返回内容
     */
    public String callAiSyncWithFile(
            String prompt,
            String sessionId,
            AgentPropertiesDO agentProperties,
            String fileUrl,
            String stage,
            String singleFlightKey) throws Exception {
        // （1）经过保护逻辑后携带文件执行模型调用
        return guardedCall(stage, singleFlightKey, () -> doChat(prompt, sessionId, agentProperties, fileUrl, null));
    }

    /**
     * 使用参数模板同步调用模型，自动从参数中提取输入并生成 single-flight 键。
     *
     * @param sessionId 会话 id
     * @param agentProperties 智能体配置
     * @param parameters 调用参数，AGENT_USER_INPUT 为输入内容
     * @return 模型返回内容
     */
    public String callAiSyncWithParameters(
            String sessionId,
            AgentPropertiesDO agentProperties,
            Map<String, Object> parameters) throws Exception {
        // （1）提取 AGENT_USER_INPUT 并生成 single-flight 键
        Object rawInput = parameters == null ? null : parameters.get("AGENT_USER_INPUT");
        String input = rawInput == null ? "" : rawInput.toString().trim();
        String key = buildSingleFlightKey(InterviewAiGuardStage.INTERVIEW_EVALUATION, sessionId, null, input);
        return callAiSyncWithParameters(
                sessionId,
                agentProperties,
                parameters,
                InterviewAiGuardStage.INTERVIEW_EVALUATION,
                key
        );
    }

    /**
     * 按指定阶段与 single-flight 键使用参数模板同步调用模型。
     *
     * @param sessionId 会话 id
     * @param agentProperties 智能体配置
     * @param parameters 调用参数
     * @param stage 调用阶段
     * @param singleFlightKey single-flight 键
     * @return 模型返回内容
     */
    public String callAiSyncWithParameters(
            String sessionId,
            AgentPropertiesDO agentProperties,
            Map<String, Object> parameters,
            String stage,
            String singleFlightKey) throws Exception {
        // （1）取参数中的输入内容，空白时使用空串
        Object rawInput = parameters == null ? null : parameters.get("AGENT_USER_INPUT");
        String input = rawInput == null ? "" : rawInput.toString().trim();
        // （2）经过保护逻辑后按参数模板执行模型调用
        return guardedCall(
                stage,
                singleFlightKey,
                () -> doChat(StrUtil.blankToDefault(input, ""), sessionId, agentProperties, null, parameters)
        );
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

    /**
     * 调用底层模型并将流式返回片段拼接为完整字符串。
     */
    private String doChat(
            String input,
            String sessionId,
            AgentPropertiesDO agentProperties,
            String fileUrl,
            Map<String, Object> parameters) throws Exception {
        // （1）创建响应缓冲，用于拼接流式返回片段
        StringBuilder aiResponse = new StringBuilder();
        // （2）会话 id 为空时生成临时标识，随后走底层对话接口
        // 本类只保留守卫 / single-flight / 熔断骨架，真正的模型调用尚未接入。
        // 6C 时把这里替换为 BanmaAgentClient 转发 Python /v1/agents/*（单路由，ADR-017）。
        throw new UnsupportedOperationException(
                "AI 调用尚未接入：等待 6C 落地 BanmaAgentClient（Python Agent 单路由）");
    }
}