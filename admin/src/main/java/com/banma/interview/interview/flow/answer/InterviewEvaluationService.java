package com.banma.interview.interview.flow.answer;

import cn.hutool.core.lang.UUID;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.banma.interview.agent.dao.entity.AgentPropertiesDO;
import com.banma.interview.common.convention.exception.ClientException;
import com.banma.interview.interview.application.guard.core.InterviewAiGuardException;
import com.banma.interview.interview.application.guard.core.InterviewAiGuardStage;
import com.banma.interview.interview.infrastructure.client.BanmaAgentClient;
import com.banma.interview.interview.infrastructure.client.dto.EvaluationReqDTO;
import com.banma.interview.interview.infrastructure.client.dto.EvaluationRespDTO;
import com.banma.interview.interview.shared.InterviewAiInvoker;
import com.banma.interview.interview.shared.InterviewResponseParser;
import com.banma.interview.interview.service.InterviewQuestionCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 面试答题评分服务：优先走评分工作流，失败时降级为 prompt 直评并统一字段格式。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InterviewEvaluationService {

    private static final String KEY_AGENT_USER_INPUT = "AGENT_USER_INPUT";
    private static final String KEY_QUESTION = "question";
    private static final String KEY_RESUME_CONTEXT = "resume_context";
    private static final String KEY_SCORE = "score";
    private static final String KEY_LOGIC_OK = "logic_ok";
    private static final String KEY_MISSING_POINTS = "missing_points";
    private static final String KEY_FEEDBACK = "feedback";
    private static final String KEY_FOLLOW_UP_NEEDED = "follow_up_needed";
    private static final String KEY_FOLLOW_UP_QUESTION = "follow_up_question";

    private static final String[] RESUME_SUMMARY_KEYS = new String[]{
            "resume_context",
            "resume_summary",
            "resumeSummary",
            "candidate_summary",
            "candidateSummary",
            "profile_summary",
            "profileSummary",
            "summary"
    };

    /** 题目、流程与得分的 Redis 缓存读写 */
    private final InterviewQuestionCacheService interviewQuestionCacheService;
    /** AI 调用唯一入口（限流熔断 + single-flight） */
    private final InterviewAiInvoker interviewAiInvoker;
    /** AI 响应防御式解析（剥围栏、别名取值、补默认值） */
    private final InterviewResponseParser interviewResponseParser;
    /** Python Agent 客户端 */
    private final BanmaAgentClient banmaAgentClient;

    /**
     * Evaluate current answer by scorer workflow and return normalized result fields.
     * 评估当前答案：先走评分工作流，解析失败时降级到 prompt 直评，最后统一归一化字段。
     */
    public Map<String, Object> evaluateAnswer(
            String sessionId,
            String requestId,
            String questionNumber,
            String questionContent,
            String answerContent,
            AgentPropertiesDO scorerAgent) {
        //（1）优先走python
        try {
            Map<String, Object> agentResult = evaluateViaAgent(
                    sessionId, requestId, questionNumber, questionContent, answerContent);
            if(agentResult != null && !agentResult.isEmpty()){
                return normalizeScorerResult(agentResult);
            }
        }catch (InterviewAiGuardException e){
            // 熔断/限流是「系统在保护自己」，不能降级成静默失败，否则会持续打挂掉的依赖
            throw e;
        } catch (Exception e) {
            log.warn("Agent evaluation failed, fallback to legacy path, sessionId: {}", sessionId, e);
        }

        // （2）旧路径兜底：工作流 → prompt 直评（doChat 未接入前必然失败，属预期）
        Map<String, Object> evaluationResult = evaluateAnswerByScorerAgent(
                sessionId, requestId, questionNumber, questionContent, answerContent, scorerAgent);
        // Fallback when scorer workflow parsing fails.
        if (evaluationResult == null || evaluationResult.isEmpty()) {
            // （3）工作流解析失败时降级到 prompt 直评，保证主链路可继续。
            String evaluationPrompt = String.format(
                    "You are a technical interview evaluator. Score the candidate answer and return strict JSON only.\n" +
                            "Question: %s\n" +
                            "Answer: %s\n" +
                            "Requirements:\n" +
                            "1. score must be an integer in [0,100].\n" +
                            "2. feedback must be concise and practical.\n" +
                            "3. missing_points must be an array of strings.\n" +
                            "4. follow_up_needed must be true or false.\n" +
                            "5. If follow_up_needed is true, follow_up_question must be non-empty.\n" +
                            "Output schema: {\"score\":0,\"feedback\":\"\",\"follow_up_needed\":true,\"follow_up_question\":\"\",\"missing_points\":[\"...\"]}",
                    questionContent, answerContent
            );
            try {
                String singleFlightKey = interviewAiInvoker.buildSingleFlightKey(
                        InterviewAiGuardStage.INTERVIEW_EVALUATION,
                        sessionId,
                        questionNumber,
                        answerContent
                ) + "|fallback";
                String aiResponse = interviewAiInvoker.callAiSync(
                        evaluationPrompt,
                        sessionId,
                        scorerAgent,
                        InterviewAiGuardStage.INTERVIEW_EVALUATION,
                        singleFlightKey
                );
                evaluationResult = interviewResponseParser.parseEvaluationResult(aiResponse);
            } catch (InterviewAiGuardException ex) {
                throw ex;
            } catch (Exception ex) {
                log.warn("Prompt fallback evaluation failed, sessionId: {}", sessionId, ex);
                return null;
            }
        }
        if (evaluationResult == null) {
            return null;
        }

        // （4）最后统一字段归一化，补全 followUp/feedback 等默认值。
        Map<String, Object> normalized = normalizeScorerResult(evaluationResult);
        inferFollowUpNeededIfMissing(normalized);
        ensureDefaultEvaluationFields(normalized);
        return normalized;
    }

    /**
     * 走 Python 评分官评分
     * <p><b>为什么不把 DTO 直接序列化成 JSON</b>：本工程 JSON 用 fastjson2，它只认
     * {@code @JSONField}、不认 Jackson 的 {@code @JsonProperty}——直接
     * {@code JSON.toJSONString(dto)} 会输出 {@code missingPoints} 这类 camelCase 键，
     * 而下游 {@code parseEvaluationResult} 按 {@code missing_points} 取值就会落空。
     * 因此这里显式组装 Map，键名与解析器约定严格一致。</p>
     *
     * @param sessionId       会话 id
     * @param requestId       上游请求 id，作为 traceId 复用，便于 Java/Python 日志对账
     * @param questionNumber  题号
     * @param questionContent 题干
     * @param answerContent   候选人作答
     * @return 归一化前的评分字段（score / feedback / missing_points / follow_up_needed / follow_up_question）
     */
    private Map<String, Object> evaluateViaAgent(
            String sessionId,
            String requestId,
            String questionNumber,
            String questionContent,
            String answerContent
    ) throws Exception {
        // (1) 简历上下文，将评分约束为：回答 + 简历 + 回答要点
        String resumeContextText = buildResumeContextText(
                interviewQuestionCacheService.getSessionResumeContext(sessionId)
        );

        // (2) 组装请求
        EvaluationReqDTO request = new EvaluationReqDTO();
        request.setTraceId(StrUtil.blankToDefault(requestId, UUID.randomUUID().toString()));
        request.setSessionId(sessionId);
        request.setQuestion(StrUtil.blankToDefault(questionContent, ""));
        request.setAnswer(StrUtil.blankToDefault(answerContent, ""));

        // resume_digest 是 List<String>：整段上下文作为一个元素传入即可（prompt 内部会用「；」拼接），
        // 空串时传空列表——传 null 会被 Pydantic 判为类型错误（422）
        request.setResumeDigest(StrUtil.isBlank(resumeContextText)
                ? Collections.emptyList()
                : Collections.singletonList(resumeContextText));

        // standard 暂无数据源 TODO
        request.setStandard(Collections.emptyList());

        //（3）走守卫链：key 与旧路径完全一致，保证同一答案不会重复调模型
        String json = interviewAiInvoker.callAiSyncWithCallable(
                InterviewAiGuardStage.INTERVIEW_EVALUATION,
                interviewAiInvoker.buildSingleFlightKey(
                        InterviewAiGuardStage.INTERVIEW_EVALUATION,
                        sessionId,
                        questionNumber,
                        answerContent),
                () -> {
                    EvaluationRespDTO response = banmaAgentClient.runEvaluation(request);
                    if (response == null) {
                        throw new ClientException("评分服务返回空响应");
                    }
                    Map<String, Object> mapped = new LinkedHashMap<>();
                    mapped.put(KEY_SCORE, response.getScore());
                    mapped.put(KEY_FEEDBACK, response.getFeedback());
                    mapped.put(KEY_MISSING_POINTS,
                            response.getMissingPoints() == null
                                    ? Collections.emptyList()
                                    : response.getMissingPoints());
                    mapped.put(KEY_FOLLOW_UP_NEEDED, Boolean.TRUE.equals(response.getFollowUpNeeded()));
                    mapped.put(KEY_FOLLOW_UP_QUESTION, response.getFollowUpQuestion());
                    return JSON.toJSONString(mapped);
                });

        //（4）使用解析器：剥代码围栏、别名取值、补默认值
        return interviewResponseParser.parseEvaluationResult(json);
    }

    /**
     * 通过评分 agent 工作流评估答案，并返回归一化后的结构化结果。
     *
     * @return 评分结果字段；工作流调用或解析异常时返回 null
     */
    private Map<String, Object> evaluateAnswerByScorerAgent(
            String sessionId,
            String requestId,
            String questionNumber,
            String questionContent,
            String answerContent,
            AgentPropertiesDO scorerAgent) {
        try {
            // （1）拼装简历上下文文本
            String resumeContextText = buildResumeContextText(
                    interviewQuestionCacheService.getSessionResumeContext(sessionId));

            // （2）构建工作流入参并输出脱敏日志
            Map<String, Object> parameters = buildScorerWorkflowParameters(
                    answerContent, questionContent, resumeContextText);

            logWorkflowParameters(
                    "scorer",
                    sessionId,
                    requestId,
                    questionNumber,
                    scorerAgent,
                    parameters
            );

            // （3）同步调用评分工作流并解析结构化结果
            String workflowResponse = interviewAiInvoker.callAiSyncWithParameters(
                    sessionId + "_score",
                    scorerAgent,
                    parameters,
                    InterviewAiGuardStage.INTERVIEW_EVALUATION,
                    interviewAiInvoker.buildSingleFlightKey(
                            InterviewAiGuardStage.INTERVIEW_EVALUATION,
                            sessionId,
                            questionNumber,
                            answerContent
                    )
            );
            Map<String, Object> parsed = interviewResponseParser.parseEvaluationResult(workflowResponse);
            return normalizeScorerResult(parsed);
        } catch (InterviewAiGuardException ex) {
            throw ex;
        } catch (Exception ex) {
            log.warn("Scorer workflow invocation failed, sessionId: {}", sessionId, ex);
            return null;
        }
    }

    /**
     * 构建评分工作流入参：候选人答案、题干与简历上下文。
     */
    private Map<String, Object> buildScorerWorkflowParameters(
            String answerContent,
            String questionContent,
            String resumeContextText) {
        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put(KEY_AGENT_USER_INPUT, answerContent);
        parameters.put(KEY_QUESTION, questionContent);
        parameters.put(KEY_RESUME_CONTEXT, resumeContextText);
        return parameters;
    }

    /**
     * 打印工作流请求日志，对答案与简历等长文本做截断。
     */
    private void logWorkflowParameters(
            String stage,
            String sessionId,
            String requestId,
            String questionNumber,
            AgentPropertiesDO agent,
            Map<String, Object> parameters) {
        // （1）过滤空值并截断敏感长文本，构造日志视图
        Map<String, Object> debugView = new LinkedHashMap<>();
        if (parameters != null) {
            parameters.forEach((key, value) -> {
                if (value == null) {
                    return;
                }
                if (KEY_AGENT_USER_INPUT.equals(key) || KEY_RESUME_CONTEXT.equals(key)) {
                    debugView.put(key, clip(String.valueOf(value), 300));
                    return;
                }
                debugView.put(key, value);
            });
        }

        log.info(
                "AI request stage={}, sessionId={}, requestId={}, questionNumber={}, agent={}, params={}",
                stage,
                sessionId,
                requestId,
                questionNumber,
                agent == null ? null : agent.getAgentName(),
                JSON.toJSONString(debugView)
        );
    }

    /**
     * 结果缺少 follow_up_needed 时，按逻辑正确性、缺失点与追问文本推断是否追问。
     */
    private void inferFollowUpNeededIfMissing(Map<String, Object> result) {
        if (result == null || result.containsKey(KEY_FOLLOW_UP_NEEDED)) {
            return;
        }
        boolean logicOk = interviewResponseParser.asBoolean(result.get(KEY_LOGIC_OK));
        List<String> missingPoints = interviewResponseParser.asStringList(result.get(KEY_MISSING_POINTS));
        String followUpQuestion = interviewResponseParser.asString(result.get(KEY_FOLLOW_UP_QUESTION));
        boolean inferredFollowUp = !logicOk
                || (missingPoints != null && !missingPoints.isEmpty())
                || StrUtil.isNotBlank(followUpQuestion);
        result.put(KEY_FOLLOW_UP_NEEDED, inferredFollowUp);
    }

    /**
     * 补齐评分结果的默认字段，避免下游读取到 null。
     */
    private void ensureDefaultEvaluationFields(Map<String, Object> result) {
        if (result == null) {
            return;
        }
        if (!result.containsKey(KEY_MISSING_POINTS)) {
            result.put(KEY_MISSING_POINTS, Collections.emptyList());
        }
        if (!result.containsKey(KEY_FEEDBACK)) {
            result.put(KEY_FEEDBACK, "");
        }
        if (!result.containsKey(KEY_FOLLOW_UP_QUESTION)) {
            result.put(KEY_FOLLOW_UP_QUESTION, "");
        }
    }

    /**
     * 归一化评分结果：兼容多种别名字段并统一为标准 key。
     */
    private Map<String, Object> normalizeScorerResult(Map<String, Object> rawResult) {
        // （1）空结果原样返回
        if (rawResult == null || rawResult.isEmpty()) {
            return rawResult;
        }

        // （2）逐字段尝试别名取值，取到后写回标准 key
        Map<String, Object> normalized = new LinkedHashMap<>(rawResult);

        Integer score = extractScoreByKeys(normalized, KEY_SCORE, "total_score", "composite_score");
        if (score != null) {
            normalized.put(KEY_SCORE, score);
        }

        Boolean logicOk = extractBooleanByKeys(normalized, KEY_LOGIC_OK, "logicOk");
        if (logicOk != null) {
            normalized.put(KEY_LOGIC_OK, logicOk);
        }

        List<String> missingPoints = extractStringListByKeys(normalized, KEY_MISSING_POINTS, "missingPoints", "lack_points");
        if (missingPoints != null) {
            normalized.put(KEY_MISSING_POINTS, missingPoints);
        }

        String feedback = extractStringByKeys(normalized, KEY_FEEDBACK, "comment", "suggestion");
        if (feedback != null) {
            normalized.put(KEY_FEEDBACK, feedback);
        }

        Boolean followUpNeeded = extractBooleanByKeys(normalized, KEY_FOLLOW_UP_NEEDED, "followUpNeeded");
        if (followUpNeeded != null) {
            normalized.put(KEY_FOLLOW_UP_NEEDED, followUpNeeded);
        }

        String followUpQuestion = extractStringByKeys(
                normalized, KEY_FOLLOW_UP_QUESTION, "followUpQuestion", "ask_to_user", "ask");
        if (followUpQuestion != null) {
            normalized.put(KEY_FOLLOW_UP_QUESTION, followUpQuestion);
        }

        return normalized;
    }

    /**
     * 按候选 key 顺序提取分数，取到第一个有效值即返回。
     */
    private Integer extractScoreByKeys(Map<String, Object> source, String... keys) {
        if (source == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            Integer score = interviewResponseParser.parseScoreFromResponse(source, key);
            if (score != null) {
                return score;
            }
        }
        return null;
    }

    /**
     * 按候选 key 顺序提取布尔值，命中第一个存在的 key 即返回。
     */
    private Boolean extractBooleanByKeys(Map<String, Object> source, String... keys) {
        if (source == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (source.containsKey(key)) {
                return interviewResponseParser.asBoolean(source.get(key));
            }
        }
        return null;
    }

    /**
     * 按候选 key 顺序提取字符串列表，命中第一个存在的 key 即返回。
     */
    private List<String> extractStringListByKeys(Map<String, Object> source, String... keys) {
        if (source == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (source.containsKey(key)) {
                return interviewResponseParser.asStringList(source.get(key));
            }
        }
        return null;
    }

    /**
     * 按候选 key 顺序提取字符串，命中第一个存在的 key 即返回。
     */
    private String extractStringByKeys(Map<String, Object> source, String... keys) {
        if (source == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (source.containsKey(key)) {
                String value = interviewResponseParser.asString(source.get(key));
                return value == null ? "" : value;
            }
        }
        return null;
    }

    /**
     * 把简历上下文压缩为文本：优先取摘要字段，否则过滤无关字段后序列化并截断。
     */
    private String buildResumeContextText(Map<String, Object> resumeContext) {
        // （1）空上下文返回空串
        if (resumeContext == null || resumeContext.isEmpty()) {
            return "";
        }

        // （2）优先使用简历摘要类字段
        String preferredSummary = extractNonBlankStringByKeys(resumeContext, RESUME_SUMMARY_KEYS);
        if (StrUtil.isNotBlank(preferredSummary)) {
            return clip(preferredSummary, 2000);
        }

        // （3）无摘要时过滤题目/建议等无关字段，再序列化截断
        Map<String, Object> filteredContext = new LinkedHashMap<>();
        resumeContext.forEach((key, value) -> {
            if (StrUtil.isBlank(key) || value == null) {
                return;
            }
            if ("questions".equals(key)
                    || "suggestions".equals(key)
                    || "sugest".equals(key)
                    || "resumeScore".equals(key)
                    || "score".equals(key)
                    || "type".equals(key)
                    || "interviewType".equals(key)) {
                return;
            }
            filteredContext.put(key, value);
        });

        Map<String, Object> contextToUse = filteredContext.isEmpty() ? resumeContext : filteredContext;
        return clip(JSON.toJSONString(contextToUse), 2000);
    }

    /**
     * 按候选 key 顺序提取第一个非空字符串。
     */
    private String extractNonBlankStringByKeys(Map<String, Object> source, String... keys) {
        if (source == null || keys == null) {
            return null;
        }
        for (String key : keys) {
            if (!source.containsKey(key)) {
                continue;
            }
            String value = interviewResponseParser.asString(source.get(key));
            if (StrUtil.isNotBlank(value)) {
                return value;
            }
        }
        return null;
    }

    /**
     * 压缩文本：合并连续空白并按最大长度截断。
     */
    private String clip(String value, int maxLen) {
        if (StrUtil.isBlank(value)) {
            return "";
        }
        String cleaned = value.trim().replaceAll("\\s+", " ");
        if (cleaned.length() <= maxLen) {
            return cleaned;
        }
        return cleaned.substring(0, maxLen);
    }
}
