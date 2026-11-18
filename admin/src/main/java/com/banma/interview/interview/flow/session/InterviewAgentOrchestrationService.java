package com.banma.interview.interview.flow.session;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.banma.interview.interview.api.io.req.DemeanorEvaluationReqDTO;
import com.banma.interview.interview.api.io.req.InterviewAnswerReqDTO;
import com.banma.interview.interview.api.io.req.InterviewQuestionReqDTO;
import com.banma.interview.interview.api.io.resp.InterviewAnswerRespDTO;
import com.banma.interview.interview.api.io.resp.InterviewQuestionRespDTO;
import com.banma.interview.interview.application.InterviewWorkflowService;
import com.banma.interview.interview.application.flow.InterviewFlowStateMachine;
import com.banma.interview.interview.application.runtime.InterviewSessionRuntimeRehydrateService;
import com.banma.interview.interview.application.runtime.InterviewRuntimeRehydrateScope;
import com.banma.interview.interview.flow.answer.InterviewAnswerPipeline;
import com.banma.interview.interview.flow.answer.InterviewQuestionLockService;
import com.banma.interview.interview.infrastructure.client.BanmaAgentClient;
import com.banma.interview.interview.infrastructure.client.dto.QuestionOfficerReqDTO;
import com.banma.interview.interview.infrastructure.client.dto.QuestionOfficerRespDTO;
import com.banma.interview.interview.service.InterviewQuestionCacheService;
import com.banma.interview.interview.service.InterviewQuestionService;
import com.banma.interview.interview.service.model.InterviewFlowState;
import com.banma.interview.interview.service.model.InterviewRuntimeLoadMode;
import com.banma.interview.interview.service.model.InterviewTurnLog;
import io.micrometer.core.instrument.Metrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Interview orchestration service, including main-question and follow-up restoration.
 * 面试编排服务：转发提取、答题、神态评估，并负责当前题解析与追问状态恢复。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InterviewAgentOrchestrationService implements InterviewWorkflowService {

    /** 题目、流程与得分的 Redis 缓存读写 */
    private final InterviewQuestionCacheService interviewQuestionCacheService;
    /** 答题主流水线 */
    private final InterviewAnswerPipeline interviewAnswerPipeline;
    /** flow 状态机（题号游标与状态迁移） */
    private final InterviewFlowStateMachine interviewFlowStateMachine;
    /** 题号级分布式锁 */
    private final InterviewQuestionLockService interviewQuestionLockService;
    /** 运行态热冷快照重水化 */
    private final InterviewSessionRuntimeRehydrateService runtimeRehydrateService;
    /** 题目主数据（DB 落库与回补） **/
    private final InterviewQuestionService  interviewQuestionService;
    /** Python Agent 客户端 */
    private final BanmaAgentClient banmaAgentClient;

    /** 出题意图标识：只有该意图才产生题目并需要落库 */
    private static final String INTENT_GENERATE = "GENERATE";

    /**
     * 编排入口：提取链路由题目提取服务完成。
     */
    public InterviewQuestionRespDTO extractInterviewQuestions(InterviewQuestionReqDTO reqDTO) {
        // 讯飞版出题提取已下线（2026-11-11）；出题链路由 Python 出题官承接（前端 7C2 已直连验证）
        throw new UnsupportedOperationException(
                "讯飞出题链路已下线：出题走 Python Agent（/v1/agents/question-officer/run）");
    }

    /**
     * 调 Python 出题官并把结果落库（实现 {@link InterviewWorkflowService#runQuestionOfficer}）。
     *
     * <p><b>落库不造轮子</b>：DB 走 {@code upsertStructuredExtraction}（题目主数据 + 缓存回补来源），
     * 缓存走 {@code cacheInterviewQuestions}（答题 Pipeline 直接读），游标走 {@code ensureInitialized}。</p>
     *
     * @param requestParam 出题请求（sessionId 必填）
     * @param userName     当前登录用户名
     * @return Python 出题官的原始响应
     */
    @Override
    public QuestionOfficerRespDTO runQuestionOfficer(QuestionOfficerReqDTO requestParam, String userName) {
        // (1)traceId 由服务端生成
        requestParam.setTraceId(UUID.randomUUID().toString());

        //(2) 调用Python出题官agent
        QuestionOfficerRespDTO response = banmaAgentClient.runQuestionOfficer(requestParam);

        //(3) 出题结果落库
        persistQuestionSet(requestParam, response, userName);

        return response;
    }

    /**
     * 编排入口：答题链路由答题流水线统一处理幂等、加锁、评分与流程推进。
     */
    public InterviewAnswerRespDTO answerInterviewQuestion(String sessionId, InterviewAnswerReqDTO requestParam) {
        // 编排入口：答题链路统一走 pipeline，避免控制逻辑分散在多个 service。
        return interviewAnswerPipeline.execute(sessionId, requestParam);
    }

    /**
     * 获取下一题：当前实现与获取当前题一致，不额外推进流程游标。
     */
    public InterviewAnswerRespDTO getNextQuestion(String sessionId) {
        return getCurrentQuestion(sessionId);
    }

    /**
     * Resolve current question by sessionId without moving the flow cursor.
     * Priority:
     * 1) flow state in Redis
     * 2) latest turn log recovery
     * 3) fallback to first question
     * 按会话解析当前题并返回给前端，不推进游标：优先 flow 缓存，其次按轮次日志恢复，最后重建 flow。
     */
    public InterviewAnswerRespDTO getCurrentQuestion(String sessionId) {
        InterviewAnswerRespDTO response = InterviewAnswerRespDTO.init();

        if (StrUtil.isBlank(sessionId)) {
            return response.fail("sessionId cannot be empty");
        }

        try {
            runtimeRehydrateService.ensureRuntime(
                    sessionId,
                    InterviewRuntimeLoadMode.READ_WRITE_REQUIRED,
                    InterviewRuntimeRehydrateScope.FLOW_ONLY
            );
            // （1）先保证题库可用（缓存 miss 时从 DB 回补）。
            Map<String, String> questions = getOrLoadQuestions(sessionId);
            if (questions == null || questions.isEmpty()) {
                return response.fail("interview questions not found");
            }

            InterviewFlowState flowState = interviewQuestionCacheService.getInterviewFlow(sessionId);
            if (flowState == null) {
                // （2）flow 丢失时按 turns 恢复；恢复失败再兜底重建 flow。
                CurrentQuestionState recoveredState = recoverCurrentQuestionFromTurns(sessionId, questions);
                if (recoveredState.finished) {
                    Metrics.counter("flow_restore_source_total", "source", "turn_finished").increment();
                    response.setTotalScore(interviewQuestionCacheService.getSessionTotalScore(sessionId));
                    return response.finish().success();
                }
                if (recoveredState.hasQuestion()) {
                    Metrics.counter("flow_restore_source_total", "source", "turn_recovered").increment();
                    flowState = restoreFlowToQuestion(sessionId, recoveredState.questionNumber, questions.size());
                    fillCurrentQuestionResponse(sessionId, response,
                            recoveredState.questionNumber, recoveredState.questionContent, flowState);
                    return response.success();
                }

                Metrics.counter("flow_restore_source_total", "source", "flow_reinit").increment();
                interviewQuestionCacheService.initInterviewFlow(sessionId, questions.size());
                flowState = interviewQuestionCacheService.getInterviewFlow(sessionId);
            } else {
                Metrics.counter("flow_restore_source_total", "source", "flow_cache").increment();
            }

            if (flowState == null) {
                return response.fail("interview flow not initialized");
            }
            if (flowState.isCompleted() || interviewFlowStateMachine.isOutOfRange(flowState)) {
                response.setTotalScore(interviewQuestionCacheService.getSessionTotalScore(sessionId));
                return response.finish().success();
            }

            // （3）在当前 flow 上解析题号并回填给前端，不推进游标。
            String questionNumber = interviewFlowStateMachine.currentQuestionNumber(flowState);
            String questionContent = resolveQuestionByNumber(sessionId, questionNumber, questions);
            if (StrUtil.isBlank(questionContent)) {
                return response.fail("question does not exist or expired");
            }

            fillCurrentQuestionResponse(sessionId, response, questionNumber, questionContent, flowState);
            return response.success();
        } catch (Exception e) {
            log.error("Failed to get current question, sessionId: {}", sessionId, e);
            return response.fail("failed to get current question: " + e.getMessage());
        }
    }

    /**
     * 编排入口：神态评估委托神态评分服务处理。
     */
    public String evaluateDemeanor(DemeanorEvaluationReqDTO reqDTO) {
        // 神态官一期不做（ADR-009），讯飞依赖已随 2026-11-11 清理移除；二期按 Python Agent 方案重建
        throw new UnsupportedOperationException("神态评估一期未启用（ADR-009）");
    }

    /**
     * 落库：题目主数据 → Redis 缓存 → 答题游标。
     * @param request  出题请求（取 sessionId / resumeUrl / directionDefault）
     * @param response Python 出题响应
     * @param userName 当前登录用户名
     */
    private void persistQuestionSet(
            QuestionOfficerReqDTO request,
            QuestionOfficerRespDTO response,
            String userName){
        // （1）只有真实出题才落库：闲聊分支（SMALL_TALK）没有题目
        if(response == null || !INTENT_GENERATE.equals(response.getIntent())
                    || CollUtil.isEmpty(response.getQuestions())) {
            return;
        }

        String sessionId = request.getSessionId();
        if(StrUtil.isBlank(sessionId)) {
            log.warn("出题结果未落库：sessionId 为空, traceId={}", response.getTraceId());
            return;
        }

        //(2) 已有答题进度时跳过，避免覆盖题目导致游标与题干错位
        InterviewFlowState flow = interviewFlowStateMachine.current(sessionId);
        if(flow != null && flow.getCurrentIndex() != null && flow.getCurrentIndex() > 0) {
            log.warn("会话已有答题进度，跳过题目覆盖: sessionId={}, currentIndex={}",
                    sessionId, flow.getCurrentIndex());
            return;
        }

        // （3）简历上下文：评分官据此判断候选人背景；同时作为原始响应兜底写入 DB
        Map<String, Object> resumeContext = buildResumeContext(request, response);

        // （4）题目主数据落库
        interviewQuestionService.upsertStructuredExtraction(
                sessionId,
                userName,
                null,
                request.getResumeUrl(),
                response.getQuestions(),
                response.getQuestions(),
                response.getResumeScore(),
                response.getResumeType(),
                resumeContext);

        // （5）写 Redis：答题 Pipeline 的 stepLoadCurrentQuestion 直接读这里
        interviewQuestionCacheService.cacheInterviewQuestions(sessionId, response.getQuestions());
        if(CollUtil.isNotEmpty(response.getResumeSuggest())) {
            interviewQuestionCacheService.cacheInterviewSuggestions(sessionId, response.getResumeSuggest());
        }
        if(response.getResumeScore() != null) {
            interviewQuestionCacheService.cacheResumeScore(sessionId, response.getResumeScore());
        }
        // 此前全项目无人调用 cacheResumeContext，导致评分时 resume_digest 恒为空
        interviewQuestionCacheService.cacheResumeContext(sessionId, resumeContext);

        //（6）初始化答题游标（幂等：已存在则复用，不重置进度）
        interviewFlowStateMachine.ensureInitialized(sessionId, response.getQuestions().size());

        log.info("出题结果已落库: sessionId={}, traceId={}, questions={}, source={}",
                sessionId, response.getTraceId(), response.getQuestions().size(), response.getQuestionsSource());
    }

    private Map<String, Object> buildResumeContext(QuestionOfficerReqDTO request, QuestionOfficerRespDTO response) {
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("resumeType", response.getResumeType());
        context.put("resumeScore", response.getResumeScore());
        context.put("resumeSuggest", response.getResumeSuggest());
        context.put("direction", request.getDirectionDefault());
        context.put("questionsSource", response.getQuestionsSource());
        return context;
    }

    /**
     * 把当前题回填到响应：当前题、下一题、追问标记与总分。
     */
    private void fillCurrentQuestionResponse(
            String sessionId,
            InterviewAnswerRespDTO response,
            String questionNumber,
            String questionContent,
            InterviewFlowState flowState) {
        response.withCurrentQuestion(questionNumber, questionContent);
        response.withNextQuestion(
                questionNumber,
                questionContent,
                isFollowUpQuestion(questionNumber),
                resolveFollowUpCount(flowState, questionNumber)
        );
        response.setTotalScore(interviewQuestionCacheService.getSessionTotalScore(sessionId));
    }

    /**
     * 读取会话题库，缓存为空时从数据库回补后重读。
     */
    private Map<String, String> getOrLoadQuestions(String sessionId) {
        Map<String, String> questions = interviewQuestionCacheService.getSessionInterviewQuestions(sessionId);
        if (questions == null || questions.isEmpty()) {
            interviewQuestionCacheService.loadInterviewQuestionsFromDatabase(sessionId);
            questions = interviewQuestionCacheService.getSessionInterviewQuestions(sessionId);
        }
        return questions;
    }

    /**
     * 按题号解析题干：先查题库集合，未命中再查缓存，仍失败时用原始题号重试。
     */
    private String resolveQuestionByNumber(String sessionId, String questionNumber, Map<String, String> questions) {
        // （1）题号归一化失败直接返回
        String normalizedQuestionNumber = normalizeQuestionNumber(questionNumber);
        if (StrUtil.isBlank(normalizedQuestionNumber)) {
            return null;
        }

        String questionContent = questions == null ? null : questions.get(normalizedQuestionNumber);
        if (StrUtil.isBlank(questionContent)) {
            questionContent = interviewQuestionCacheService.getQuestionByNumber(sessionId, normalizedQuestionNumber);
        }
        // （2）归一化题号未命中时用原始题号再查一次
        if (StrUtil.isBlank(questionContent) && !normalizedQuestionNumber.equals(questionNumber)) {
            questionContent = questions == null ? null : questions.get(questionNumber);
            if (StrUtil.isBlank(questionContent)) {
                questionContent = interviewQuestionCacheService.getQuestionByNumber(sessionId, questionNumber);
            }
        }
        return questionContent;
    }

    /**
     * 按轮次日志恢复当前题：优先 nextQuestionNumber，其次题目内容反查，最后按已答题号顺延。
     */
    private CurrentQuestionState recoverCurrentQuestionFromTurns(String sessionId, Map<String, String> questions) {
        // （1）无轮次记录时返回空态
        List<InterviewTurnLog> turns = interviewQuestionCacheService.getInterviewTurns(sessionId);
        if (turns == null || turns.isEmpty()) {
            return CurrentQuestionState.empty();
        }

        InterviewTurnLog latestTurn = turns.get(turns.size() - 1);
        if (latestTurn == null) {
            return CurrentQuestionState.empty();
        }
        // （2）最后一条轮次已标记结束则直接返回完成态
        if (Boolean.TRUE.equals(latestTurn.getFinished())) {
            return CurrentQuestionState.finished();
        }

        // （3）优先按下一题号定位题干
        String nextQuestionNumber = normalizeQuestionNumber(latestTurn.getNextQuestionNumber());
        String nextQuestionContent = resolveQuestionByNumber(sessionId, nextQuestionNumber, questions);
        if (StrUtil.isNotBlank(nextQuestionNumber) && StrUtil.isNotBlank(nextQuestionContent)) {
            return CurrentQuestionState.of(nextQuestionNumber, nextQuestionContent);
        }

        // （4）题号不可用时按下一题内容反查题号
        String nextQuestionFromTurn = latestTurn.getNextQuestion();
        if (StrUtil.isNotBlank(nextQuestionFromTurn)) {
            String matchedQuestionNumber = matchQuestionNumberByContent(sessionId, nextQuestionFromTurn, questions);
            if (StrUtil.isNotBlank(matchedQuestionNumber)) {
                String matchedQuestionContent = resolveQuestionByNumber(sessionId, matchedQuestionNumber, questions);
                if (StrUtil.isNotBlank(matchedQuestionContent)) {
                    return CurrentQuestionState.of(matchedQuestionNumber, matchedQuestionContent);
                }
            }
        }

        // （5）兜底按已答主问题号顺延一题
        Integer answeredQuestionNo = extractMainQuestionNo(normalizeQuestionNumber(latestTurn.getQuestionNumber()));
        if (answeredQuestionNo != null) {
            int candidateQuestionNo = answeredQuestionNo + 1;
            if (questions != null && !questions.isEmpty() && candidateQuestionNo <= questions.size()) {
                String candidateQuestionNumber = String.valueOf(candidateQuestionNo);
                String candidateQuestionContent = resolveQuestionByNumber(sessionId, candidateQuestionNumber, questions);
                if (StrUtil.isNotBlank(candidateQuestionContent)) {
                    return CurrentQuestionState.of(candidateQuestionNumber, candidateQuestionContent);
                }
            }
        }

        return CurrentQuestionState.empty();
    }

    /**
     * 按题号把 flow 恢复到对应主问题，必要时补齐追问次数，恢复过程加分布式锁防并发覆盖。
     */
    private InterviewFlowState restoreFlowToQuestion(String sessionId, String questionNumber, int totalQuestions) {
        if (StrUtil.isBlank(sessionId) || StrUtil.isBlank(questionNumber) || totalQuestions <= 0) {
            return null;
        }
        RLock lock = null;
        try {
            // （1）先按题号加锁恢复，防止并发恢复互相覆盖。
            lock = interviewQuestionLockService.acquire(sessionId, questionNumber);
            if (lock == null) {
                return interviewQuestionCacheService.getInterviewFlow(sessionId);
            }
            InterviewFlowState existingFlowState = interviewQuestionCacheService.getInterviewFlow(sessionId);
            if (existingFlowState != null) {
                return existingFlowState;
            }

            // （2）先初始化 flow，再把主问题游标推进到目标主问题。
            interviewQuestionCacheService.initInterviewFlow(sessionId, totalQuestions);
            Integer mainQuestionNo = extractMainQuestionNo(questionNumber);
            if (mainQuestionNo == null || mainQuestionNo <= 0) {
                return interviewQuestionCacheService.getInterviewFlow(sessionId);
            }

            int targetMainQuestionNo = Math.min(mainQuestionNo, totalQuestions);
            for (int currentMainQuestionNo = 1; currentMainQuestionNo < targetMainQuestionNo; currentMainQuestionNo++) {
                InterviewFlowState advancedFlow = interviewFlowStateMachine.advanceMainQuestion(sessionId);
                if (advancedFlow == null || interviewFlowStateMachine.isCompleted(advancedFlow)) {
                    return advancedFlow;
                }
            }

            // （3）如果目标是追问题，再补齐 followUp 次数，保证题号与 flow 状态一致。
            if (isFollowUpQuestion(questionNumber)) {
                int followUpCount = extractFollowUpCount(questionNumber);
                for (int index = 0; index < followUpCount; index++) {
                    interviewFlowStateMachine.startFollowUpQuestion(sessionId, questionNumber);
                }
            }
            return interviewQuestionCacheService.getInterviewFlow(sessionId);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return interviewQuestionCacheService.getInterviewFlow(sessionId);
        } finally {
            interviewQuestionLockService.release(lock);
        }
    }

    /**
     * 按题目内容反查题号：先查主问题，再查追问缓存。
     */
    private String matchQuestionNumberByContent(String sessionId, String questionContent, Map<String, String> questions) {
        String matchedMainQuestionNumber = matchQuestionNumberByContent(questionContent, questions);
        if (StrUtil.isNotBlank(matchedMainQuestionNumber)) {
            return matchedMainQuestionNumber;
        }
        Map<String, String> followUpQuestions = interviewQuestionCacheService.getSessionFollowUpQuestions(sessionId);
        return matchQuestionNumberByContent(questionContent, followUpQuestions);
    }

    /**
     * 在题目映射中按内容精确匹配题号，未匹配返回 null。
     */
    private String matchQuestionNumberByContent(String questionContent, Map<String, String> questions) {
        if (StrUtil.isBlank(questionContent) || questions == null || questions.isEmpty()) {
            return null;
        }
        String target = questionContent.trim();
        for (Map.Entry<String, String> entry : questions.entrySet()) {
            if (entry == null || StrUtil.isBlank(entry.getValue())) {
                continue;
            }
            if (target.equals(entry.getValue().trim())) {
                return normalizeQuestionNumber(entry.getKey());
            }
        }
        return null;
    }

    /**
     * 解析正整数，非法或非正数返回 null。
     */
    private Integer parsePositiveInt(String value) {
        if (StrUtil.isBlank(value)) {
            return null;
        }
        try {
            int parsed = Integer.parseInt(value);
            return parsed > 0 ? parsed : null;
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * 从题号中解析主问题序号，追问题号取主问题部分。
     */
    private Integer extractMainQuestionNo(String questionNumber) {
        String mainQuestionNumber = resolveMainQuestionNumber(questionNumber);
        return parsePositiveInt(mainQuestionNumber);
    }

    /**
     * 解析当前追问题对应的追问次数，题号解析失败时回退 flow 记录。
     */
    private Integer resolveFollowUpCount(InterviewFlowState flowState, String questionNumber) {
        if (!isFollowUpQuestion(questionNumber)) {
            return 0;
        }
        int parsedFollowUpCount = extractFollowUpCount(questionNumber);
        if (parsedFollowUpCount > 0) {
            return parsedFollowUpCount;
        }
        if (flowState != null && flowState.getFollowUpCount() != null) {
            return Math.max(flowState.getFollowUpCount(), 0);
        }
        return 0;
    }

    /**
     * 判断题号是否为追问题（形如 1-F2）。
     */
    private boolean isFollowUpQuestion(String questionNumber) {
        return StrUtil.isNotBlank(questionNumber) && questionNumber.trim().matches("\\d+-F\\d+");
    }

    /**
     * 从 -F 后缀中解析追问次数，格式非法返回 0。
     */
    private int extractFollowUpCount(String questionNumber) {
        if (!isFollowUpQuestion(questionNumber)) {
            return 0;
        }
        int separatorIndex = questionNumber.indexOf("-F");
        if (separatorIndex < 0 || separatorIndex + 2 >= questionNumber.length()) {
            return 0;
        }
        try {
            return Math.max(Integer.parseInt(questionNumber.substring(separatorIndex + 2).trim()), 0);
        } catch (Exception ex) {
            return 0;
        }
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
     * 归一化题号：去空格，纯数字题号去掉前导零。
     */
    private String normalizeQuestionNumber(String questionNumber) {
        if (StrUtil.isBlank(questionNumber)) {
            return null;
        }
        String normalized = questionNumber.trim();
        if (normalized.matches("\\d+")) {
            try {
                return String.valueOf(Integer.parseInt(normalized));
            } catch (Exception ex) {
                return normalized;
            }
        }
        return normalized;
    }

    /**
     * 当前题恢复结果：是否已结束、题号与题干。
     */
    private static final class CurrentQuestionState {
        /** 是否已达结束态 */
        private final boolean finished;
        /** 题号 */
        private final String questionNumber;
        /** 题干内容 */
        private final String questionContent;

        private CurrentQuestionState(boolean finished, String questionNumber, String questionContent) {
            this.finished = finished;
            this.questionNumber = questionNumber;
            this.questionContent = questionContent;
        }

        private static CurrentQuestionState finished() {
            return new CurrentQuestionState(true, null, null);
        }

        private static CurrentQuestionState of(String questionNumber, String questionContent) {
            return new CurrentQuestionState(false, questionNumber, questionContent);
        }

        private static CurrentQuestionState empty() {
            return new CurrentQuestionState(false, null, null);
        }

        private boolean hasQuestion() {
            return StrUtil.isNotBlank(questionNumber) && StrUtil.isNotBlank(questionContent);
        }
    }
}
