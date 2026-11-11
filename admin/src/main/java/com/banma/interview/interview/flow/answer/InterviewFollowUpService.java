package com.banma.interview.interview.flow.answer;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.banma.interview.agent.application.BusinessAgentResolver;
import com.banma.interview.agent.application.BusinessAgentScene;
import com.banma.interview.agent.dao.entity.AgentPropertiesDO;
import com.banma.interview.interview.application.guard.core.InterviewAiGuardException;
import com.banma.interview.interview.application.guard.core.InterviewAiGuardStage;
import com.banma.interview.interview.shared.InterviewAiInvoker;
import com.banma.interview.interview.shared.InterviewResponseParser;
import com.banma.interview.interview.service.InterviewQuestionCacheService;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 面试追问服务：优先调用追问工作流生成问题，失败时回退到评分器建议。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InterviewFollowUpService {

    private static final String KEY_AGENT_USER_INPUT = "AGENT_USER_INPUT";
    private static final String KEY_MODE = "mode";
    private static final String KEY_FOLLOW_UP_COUNT = "follow_up_count";
    private static final String KEY_MAX_FOLLOW_UP = "max_follow_up";
    private static final String KEY_QUESTION = "question";
    private static final String KEY_RESUME_CONTEXT = "resume_context";
    private static final String KEY_ASK_TO_USER = "ask_to_user";
    private static final String KEY_END_INTERVIEW = "end_interview";

    /** 按业务场景解析智能体配置（模型路由入口） */
    private final BusinessAgentResolver businessAgentResolver;
    /** 题目、流程与得分的 Redis 缓存读写 */
    private final InterviewQuestionCacheService interviewQuestionCacheService;
    /** AI 调用唯一入口（限流熔断 + single-flight） */
    private final InterviewAiInvoker interviewAiInvoker;
    /** AI 响应防御式解析（剥围栏、别名取值、补默认值） */
    private final InterviewResponseParser interviewResponseParser;

    /**
     * 生成追问问题：检查追问次数上限后优先调用工作流，失败时回退到评分器建议问题。
     *
     * @return 追问结果；超过上限或无可用的追问内容时返回空结果
     */
    public FollowUpQuestionResult generateFollowUpQuestion(
            String sessionId,
            String requestId,
            String currentQuestionNumber,
            String currentQuestion,
            String answerContent,
            String fallbackFollowUpQuestion,
            Integer currentFollowUpCount,
            Integer maxFollowUp) {

        // （1）先做追问次数与输入兜底，超过上限直接停止追问。
        int safeCurrentFollowUpCount = Math.max(0, currentFollowUpCount == null ? 0 : currentFollowUpCount);
        int safeMaxFollowUp = maxFollowUp == null || maxFollowUp <= 0 ? 2 : maxFollowUp;
        String sanitizedFallbackQuestion = sanitizeFollowUpQuestion(fallbackFollowUpQuestion);
        if (safeCurrentFollowUpCount >= safeMaxFollowUp) {
            return FollowUpQuestionResult.empty();
        }

        // （2）计算主问题编号与本次追问题号。
        String mainQuestionNumber = resolveMainQuestionNumber(currentQuestionNumber);
        String questionNumber = buildFollowUpQuestionNumber(mainQuestionNumber, safeCurrentFollowUpCount + 1);
        if (StrUtil.isBlank(questionNumber)) {
            return FollowUpQuestionResult.empty();
        }

        String generatedQuestion = null;
        try {
            // （3）优先调用追问工作流生成更针对性的追问。
            AgentPropertiesDO agentProperties = businessAgentResolver.resolveRequired(BusinessAgentScene.INTERVIEW_QUESTION_ASKING);
            generatedQuestion = invokeFollowUpWorkflow(
                    sessionId,
                    requestId,
                    currentQuestion,
                    answerContent,
                    safeCurrentFollowUpCount,
                    safeMaxFollowUp,
                    agentProperties
            );
        } catch (Exception ex) {
            log.warn("Follow-up agent unavailable, fallback to scorer suggestion, sessionId={}", sessionId, ex);
        }

        // （4）工作流失败时回退到评分器建议问题，仍可继续流程。
        String questionContent = StrUtil.isNotBlank(generatedQuestion) ? generatedQuestion : sanitizedFallbackQuestion;
        if (StrUtil.isBlank(questionContent)) {
            return FollowUpQuestionResult.empty();
        }

        return new FollowUpQuestionResult(questionNumber, questionContent, safeCurrentFollowUpCount + 1);
    }

    /**
     * 调用追问工作流生成追问文本，任何异常或结束标记均返回 null 由上层回退。
     */
    private String invokeFollowUpWorkflow(
            String sessionId,
            String requestId,
            String currentQuestion,
            String answerContent,
            int currentFollowUpCount,
            int maxFollowUp,
            AgentPropertiesDO agentProperties) {

        // （1）入参不完整时不发起 AI 调用
        if (agentProperties == null || StrUtil.isBlank(currentQuestion) || StrUtil.isBlank(answerContent)) {
            return null;
        }

        try {
            // （2）组装工作流入参并调用追问工作流
            Map<String, Object> parameters = buildWorkflowParameters(
                    answerContent,
                    currentQuestion,
                    currentFollowUpCount,
                    maxFollowUp,
                    buildResumeContextText(interviewQuestionCacheService.getSessionResumeContext(sessionId))
            );
            log.info(
                    "Follow-up workflow request, sessionId={}, requestId={}, question={}, followUpCount={}, maxFollowUp={}",
                    sessionId,
                    requestId,
                    clip(currentQuestion, 120),
                    currentFollowUpCount,
                    maxFollowUp
            );
            String workflowResponse;
            String singleFlightKey = interviewAiInvoker.buildSingleFlightKey(
                    InterviewAiGuardStage.INTERVIEW_FOLLOWUP,
                    sessionId,
                    currentQuestion,
                    answerContent
            );
            workflowResponse = interviewAiInvoker.callAiSyncWithParameters(
                    sessionId,
                    agentProperties,
                    parameters,
                    InterviewAiGuardStage.INTERVIEW_FOLLOWUP,
                    singleFlightKey
            );
            // （3）工作流返回错误或标记结束面试时，放弃本轮追问
            String workflowErrorMessage = interviewResponseParser.extractWorkflowErrorMessage(workflowResponse);
            if (StrUtil.isNotBlank(workflowErrorMessage)) {
                log.warn("Follow-up workflow returned error, sessionId={}, message={}", sessionId, workflowErrorMessage);
                return null;
            }

            Map<String, Object> workflowResult = interviewResponseParser.extractStructuredResult(
                    workflowResponse,
                    KEY_ASK_TO_USER,
                    KEY_END_INTERVIEW
            );
            if (workflowResult != null && interviewResponseParser.asBoolean(workflowResult.get(KEY_END_INTERVIEW))) {
                return null;
            }

            // （4）优先取 ask_to_user 字段，缺失时从原始响应中抽取正文
            String askToUser = workflowResult == null ? null : interviewResponseParser.asString(workflowResult.get(KEY_ASK_TO_USER));
            if (StrUtil.isBlank(askToUser)) {
                askToUser = interviewResponseParser.extractContentFromInterviewResponse(workflowResponse);
            }
            return sanitizeFollowUpQuestion(askToUser);
        } catch (InterviewAiGuardException ex) {
            log.warn("Follow-up workflow fast-failed, sessionId={}, code={}", sessionId, ex.getErrorCode());
            return null;
        } catch (Exception ex) {
            log.warn("Failed to invoke follow-up workflow, sessionId={}", sessionId, ex);
            return null;
        }
    }

    /**
     * 构建追问工作流入参：答案、模式、追问计数、题干与简历上下文。
     */
    private Map<String, Object> buildWorkflowParameters(
            String answerContent,
            String currentQuestion,
            int currentFollowUpCount,
            int maxFollowUp,
            String resumeContextText) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put(KEY_AGENT_USER_INPUT, answerContent);
        parameters.put(KEY_MODE, "FOLLOW_UP");
        parameters.put(KEY_FOLLOW_UP_COUNT, currentFollowUpCount);
        parameters.put(KEY_MAX_FOLLOW_UP, maxFollowUp);
        parameters.put(KEY_QUESTION, currentQuestion);
        parameters.put(KEY_RESUME_CONTEXT, resumeContextText);
        return parameters;
    }

    /**
     * 把简历上下文序列化为文本并截断，供工作流入参使用。
     */
    private String buildResumeContextText(Map<String, Object> resumeContext) {
        if (resumeContext == null || resumeContext.isEmpty()) {
            return "";
        }
        return clip(JSON.toJSONString(resumeContext), 2000);
    }

    /**
     * 清洗追问文本：过滤无效占位值、补问号并截断到 100 字。
     */
    private String sanitizeFollowUpQuestion(String question) {
        // （1）空值直接返回
        if (StrUtil.isBlank(question)) {
            return null;
        }
        // （2）过滤 none/null/N/A 等无效占位值
        String normalized = question.trim();
        if ("none".equalsIgnoreCase(normalized)
                || "null".equalsIgnoreCase(normalized)
                || "N/A".equalsIgnoreCase(normalized)
                || "-".equals(normalized)
                || "__FINISH__".equalsIgnoreCase(normalized)) {
            return null;
        }
        if (!normalized.endsWith("?")) {
            normalized = normalized + "?";
        }
        // （3）统一截断长度，避免追问过长
        return clip(normalized, 100);
    }

    /**
     * 从追问题号中取回主问题号，如 2-F1 取 2。
     */
    private String resolveMainQuestionNumber(String questionNumber) {
        if (StrUtil.isBlank(questionNumber)) {
            return null;
        }
        String normalized = questionNumber.trim();
        int separatorIndex = normalized.indexOf("-F");
        if (separatorIndex > 0) {
            return normalized.substring(0, separatorIndex);
        }
        return normalized;
    }

    /**
     * 按“主问题号-F追问次数”拼接追问题号。
     */
    private String buildFollowUpQuestionNumber(String mainQuestionNumber, int followUpCount) {
        if (StrUtil.isBlank(mainQuestionNumber) || followUpCount <= 0) {
            return null;
        }
        return mainQuestionNumber + "-F" + followUpCount;
    }

    /**
     * 按最大长度截断文本，未超长时原样返回。
     */
    private String clip(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    /**
     * 追问生成结果：追问题号、追问内容与当前追问次数。
     */
    @Getter
    public static final class FollowUpQuestionResult {
        /** 题号 */
        private final String questionNumber;
        /** 题干内容 */
        private final String questionContent;
        /** 当前主问题已追问次数 */
        private final Integer followUpCount;

        private FollowUpQuestionResult(String questionNumber, String questionContent, Integer followUpCount) {
            this.questionNumber = questionNumber;
            this.questionContent = questionContent;
            this.followUpCount = followUpCount;
        }

        public static FollowUpQuestionResult empty() {
            return new FollowUpQuestionResult(null, null, 0);
        }

        /**
         * 是否成功生成可用的追问（题号与内容均非空）。
         */
        public boolean hasQuestion() {
            return StrUtil.isNotBlank(questionNumber) && StrUtil.isNotBlank(questionContent);
        }
    }
}


