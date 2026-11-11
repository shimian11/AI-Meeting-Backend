package com.banma.interview.interview.flow.answer;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.banma.interview.agent.application.BusinessAgentResolver;
import com.banma.interview.agent.application.BusinessAgentScene;
import com.banma.interview.agent.dao.entity.AgentPropertiesDO;
import com.banma.interview.interview.api.io.req.InterviewAnswerReqDTO;
import com.banma.interview.interview.api.io.resp.InterviewAnswerRespDTO;
import com.banma.interview.interview.shared.InterviewResponseParser;
import com.banma.interview.interview.application.flow.InterviewFlowStateMachine;
import com.banma.interview.interview.application.guard.core.InterviewAiGuardException;
import com.banma.interview.interview.application.runtime.InterviewRuntimeRehydrateScope;
import com.banma.interview.interview.application.runtime.InterviewSessionRuntimeRehydrateService;
import com.banma.interview.interview.application.runtime.InterviewSessionRuntimeSnapshotService;
import com.banma.interview.interview.application.runtime.InterviewSessionRuntimeView;
import com.banma.interview.interview.application.rule.InterviewFollowUpRuleContext;
import com.banma.interview.interview.application.rule.InterviewFollowUpRuleDecision;
import com.banma.interview.interview.application.rule.InterviewFollowUpRuleService;
import com.banma.interview.interview.service.InterviewQuestionCacheService;
import com.banma.interview.interview.service.model.InterviewFlowState;
import com.banma.interview.interview.service.model.InterviewRuntimeLoadMode;
import com.banma.interview.interview.service.model.InterviewTurnLog;
import io.micrometer.core.instrument.Metrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 面试答题主流水线：串联参数校验、幂等门禁、题目加锁、评分与流程推进。
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class InterviewAnswerPipeline {

    private static final String PROCESSING_MESSAGE = "current request is processing, please retry later";
    private static final String QUESTION_LOCK_MESSAGE = "current question is processing, please retry later";
    private static final String STALE_QUESTION_MESSAGE = "stale question number, please refresh current question";

    /** 按业务场景解析智能体配置（模型路由入口） */
    private final BusinessAgentResolver businessAgentResolver;
    /** 题目、流程与得分的 Redis 缓存读写 */
    private final InterviewQuestionCacheService interviewQuestionCacheService;
    /** 评分服务 */
    private final InterviewEvaluationService interviewEvaluationService;
    /** 追问生成与回落 */
    private final InterviewFollowUpService interviewFollowUpService;
    /** AI 响应防御式解析（剥围栏、别名取值、补默认值） */
    private final InterviewResponseParser interviewResponseParser;
    /** flow 状态机（题号游标与状态迁移） */
    private final InterviewFlowStateMachine interviewFlowStateMachine;
    /** 答题幂等门禁（NEW/PROCESSING/SUCCEEDED） */
    private final InterviewAnswerIdempotencyService interviewAnswerIdempotencyService;
    /** 题号级分布式锁 */
    private final InterviewQuestionLockService interviewQuestionLockService;
    /** 追问规则裁决（LiteFlow 规则链入口） */
    private final InterviewFollowUpRuleService interviewFollowUpRuleService;
    /** 轮次日志写入失败的补偿队列 */
    private final InterviewTurnRepairService interviewTurnRepairService;
    /** 运行态快照读写与刷新触发 */
    private final InterviewSessionRuntimeSnapshotService runtimeSnapshotService;
    /** 运行态热冷快照重水化 */
    private final InterviewSessionRuntimeRehydrateService runtimeRehydrateService;

    /**
     * 执行答题主流程：参数校验、幂等门禁、按题加锁、评分、推进流程并提交分数。
     */
    public InterviewAnswerRespDTO execute(String sessionId, InterviewAnswerReqDTO requestParam) {
        InterviewAnswerPipelineContext ctx = new InterviewAnswerPipelineContext();
        ctx.sessionId = sessionId;
        ctx.requestParam = requestParam;
        ctx.response = InterviewAnswerRespDTO.init();

        try {
            // （1）基础参数校验。
            if (!validateRequest(ctx)) {
                return ctx.response;
            }
            // （2）归一化 requestId，保证幂等键稳定。
            normalizeRequestId(ctx);
            // （3）幂等门禁：命中已成功请求直接回放，处理中请求快速失败。
            if (!stepIdempotency(ctx)) {
                return ctx.response;
            }
            // （4）读取当前题与 flow，拒绝过期题号。
            if (!stepLoadCurrentQuestion(ctx)) {
                return finishAndReturn(ctx, false);
            }
            // （5）以“当前题号”为粒度加锁，串行化同题并发提交。
            if (!stepAcquireQuestionLock(ctx)) {
                return ctx.response;
            }
            // （6）加锁后再次校验题号，避免锁前后游标漂移导致串题。
            if (!stepValidateQuestionAfterLock(ctx)) {
                return ctx.response;
            }
            // （7）调评分链路并提取结构化评分结果（此时仅计算，不入账）。
            if (!stepEvaluateAndScore(ctx)) {
                return ctx.response;
            }
            // （8）推进 flow、提交分数并组装下一题/结束态响应。
            if (!stepAdvanceFlowAndAssemble(ctx)) {
                return ctx.response;
            }
            return finishAndReturn(ctx, true);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while executing interview answer pipeline, sessionId: {}", sessionId);
            recordAnswerPipelineFailure("interrupted");
            ctx.response.fail("interview answer request interrupted");
            return ctx.response;
        } catch (Exception ex) {
            log.error("Failed to execute interview answer pipeline, sessionId: {}", sessionId, ex);
            recordAnswerPipelineFailure("unexpected_exception");
            return ctx.response.fail("failed to process answer: " + ex.getMessage());
        } finally {
            interviewQuestionLockService.release(ctx.questionLock);
            if (ctx.idempotencyStarted && !ctx.idempotencyMarkedSucceeded) {
                interviewAnswerIdempotencyService.clearProcessing(ctx.sessionId, ctx.requestId);
            }
        }
    }

    /**
     * 成功路径收尾：补写轮次日志、记录幂等回放并刷新运行时快照。
     */
    private InterviewAnswerRespDTO finishAndReturn(InterviewAnswerPipelineContext ctx, boolean appendTurn) {
        // （1）仅在响应成功时提交幂等回放，避免失败结果被缓存
        if (Boolean.TRUE.equals(ctx.response.getIsSuccess())) {
            // （2）补写轮次日志，失败则转入补偿队列
            if (appendTurn && !stepAppendTurnLog(ctx)) {
                interviewTurnRepairService.enqueue(ctx.sessionId, ctx.turnLog, "append_failed_before_mark_succeeded");
            }
            // （3）记录成功回放并刷新运行时快照
            interviewAnswerIdempotencyService.markSucceeded(ctx.sessionId, ctx.requestId, ctx.response);
            ctx.idempotencyMarkedSucceeded = true;
            if (ctx.turnLog != null) {
                runtimeSnapshotService.refreshAfterAnswerCommitted(ctx.sessionId, ctx.requestId, ctx.turnLog);
            }
        }
        return ctx.response;
    }

    /**
     * 校验答题请求的会话、请求体、题号与答案内容是否齐全。
     *
     * @return 校验通过返回 true，任一必填项缺失返回 false 并写入错误信息
     */
    private boolean validateRequest(InterviewAnswerPipelineContext ctx) {
        // （1）逐项校验必填字段，缺失时直接返回业务失败
        if (StrUtil.isBlank(ctx.sessionId)) {
            ctx.response.fail("sessionId cannot be empty");
            return false;
        }
        if (ctx.requestParam == null) {
            ctx.response.fail("request body cannot be empty");
            return false;
        }
        if (StrUtil.isBlank(ctx.requestParam.getQuestionNumber())) {
            ctx.response.fail("question number cannot be empty");
            return false;
        }
        if (StrUtil.isBlank(ctx.requestParam.getAnswerContent())) {
            ctx.response.fail("answer content cannot be empty");
            return false;
        }
        return true;
    }

    /**
     * 归一化 requestId：缺失时按会话、题号与答案生成稳定哈希，保证重试命中同一幂等键。
     */
    private void normalizeRequestId(InterviewAnswerPipelineContext ctx) {
        // （1）requestId 为空时基于内容哈希生成，保证同内容重试幂等
        String requestId = ctx.requestParam.getRequestId();
        if (StrUtil.isBlank(requestId)) {
            String seed = ctx.sessionId + "|" + ctx.requestParam.getQuestionNumber().trim() + "|" + ctx.requestParam.getAnswerContent();
            requestId = "auto-" + DigestUtil.sha256Hex(seed).substring(0, 32);
            ctx.requestParam.setRequestId(requestId);
        } else {
            // （2）非空时仅去除首尾空格
            requestId = requestId.trim();
            ctx.requestParam.setRequestId(requestId);
        }
        ctx.requestId = requestId;
    }

    /**
     * 幂等门禁：已成功的请求直接回放结果，处理中的请求快速失败，新请求继续后续流程。
     */
    private boolean stepIdempotency(InterviewAnswerPipelineContext ctx) {
        // （1）尝试开启本次请求，读取幂等判定结果
        InterviewAnswerIdempotencyService.TryStartResult tryStartResult =
                interviewAnswerIdempotencyService.tryStart(ctx.sessionId, ctx.requestId);
        // （2）按状态分支：成功回放、处理中失败、新请求继续
        switch (tryStartResult.getStatus()) {
            case SUCCEEDED -> {
                InterviewAnswerRespDTO replayResponse = tryStartResult.getReplayResponse();
                if (replayResponse != null) {
                    Metrics.counter("idempotency_replay_hit_total").increment();
                    ctx.response = replayResponse;
                    return false;
                }
                ctx.response.fail(PROCESSING_MESSAGE);
                return false;
            }
            case PROCESSING -> {
                ctx.response.fail(PROCESSING_MESSAGE);
                return false;
            }
            case NEW -> {
                // （3）新请求再查一次快照回放，兜底幂等缓存丢失场景
                ctx.idempotencyStarted = true;
                InterviewAnswerRespDTO replayResponse = runtimeSnapshotService.findReplayResponse(
                        ctx.sessionId,
                        ctx.requestId,
                        ctx.requestParam.getQuestionNumber(),
                        ctx.requestParam.getAnswerContent()
                );
                if (replayResponse != null) {
                    interviewAnswerIdempotencyService.markSucceeded(ctx.sessionId, ctx.requestId, replayResponse);
                    ctx.idempotencyMarkedSucceeded = true;
                    ctx.response = replayResponse;
                    return false;
                }
                return true;
            }
            default -> {
                ctx.response.fail(PROCESSING_MESSAGE);
                return false;
            }
        }
    }

    /**
     * 以会话与当前题号为粒度获取分布式锁，未抢到锁时按题目并发快速失败。
     */
    private boolean stepAcquireQuestionLock(InterviewAnswerPipelineContext ctx) throws InterruptedException {
        // （1）按 sessionId + 题号加锁，避免同题并发重复评分
        RLock questionLock = interviewQuestionLockService.acquire(ctx.sessionId, ctx.currentQuestionNumber);
        if (questionLock == null) {
            Metrics.counter("question_lock_contention_total").increment();
            recordAnswerPipelineFailure("question_lock_contention");
            ctx.response.fail(QUESTION_LOCK_MESSAGE);
            return false;
        }
        ctx.questionLock = questionLock;
        return true;
    }

    /**
     * 加载会话运行时与 flow，校验题号是否过期，并回填当前题内容与追问上下文。
     */
    private boolean stepLoadCurrentQuestion(InterviewAnswerPipelineContext ctx) {
        // （1）确保运行时已恢复且可写
        InterviewSessionRuntimeView runtimeView = runtimeRehydrateService.ensureRuntime(
                ctx.sessionId,
                InterviewRuntimeLoadMode.READ_WRITE_REQUIRED,
                InterviewRuntimeRehydrateScope.HOT_RUNTIME
        );
        if (runtimeView != null && !runtimeView.canWrite() && !runtimeView.isTerminal()) {
            ctx.response.fail("interview runtime restored as read-only");
            return false;
        }
        // （2）flow 缺失时按题库初始化
        ctx.flowState = ensureInterviewFlow(ctx.sessionId);
        if (ctx.flowState == null) {
            ctx.response.fail("interview flow not initialized");
            return false;
        }
        // （3）已完成或题号越界时直接返回结束态
        if (interviewFlowStateMachine.isCompleted(ctx.flowState)) {
            ctx.response.setTotalScore(interviewQuestionCacheService.getSessionTotalScore(ctx.sessionId));
            ctx.response.finish().success();
            return false;
        }
        if (interviewFlowStateMachine.isOutOfRange(ctx.flowState)) {
            interviewFlowStateMachine.markCompleted(ctx.sessionId);
            ctx.response.setTotalScore(interviewQuestionCacheService.getSessionTotalScore(ctx.sessionId));
            ctx.response.finish().success();
            return false;
        }

        // （4）解析当前题号、题干与追问信息
        ctx.currentQuestionNumber = interviewFlowStateMachine.currentQuestionNumber(ctx.flowState);
        ctx.currentQuestion = getQuestionWithReload(ctx.sessionId, ctx.currentQuestionNumber);
        if (StrUtil.isBlank(ctx.currentQuestion)) {
            ctx.response.fail("question does not exist or expired");
            return false;
        }

        ctx.currentIsFollowUp = isFollowUpQuestion(ctx.currentQuestionNumber);
        ctx.currentFollowUpCount = resolveFollowUpCount(ctx.flowState, ctx.currentQuestionNumber);
        ctx.maxFollowUp = resolveMaxFollowUp(ctx.flowState);
        ctx.response.withCurrentQuestion(ctx.currentQuestionNumber, ctx.currentQuestion);
        // （5）请求题号与当前题号不一致时按过期题拒绝
        if (!isRequestedQuestionCurrent(ctx.requestParam.getQuestionNumber(), ctx.currentQuestionNumber)) {
            Metrics.counter("stale_question_reject_total").increment();
            recordAnswerPipelineFailure("stale_question_reject");
            ctx.response.fail(STALE_QUESTION_MESSAGE);
            return false;
        }
        return true;
    }

    /**
     * 加锁后再次读取 flow 与题干，防止锁等待期间游标漂移导致串题。
     */
    private boolean stepValidateQuestionAfterLock(InterviewAnswerPipelineContext ctx) {
        // （1）重新读取加锁后的 flow 当前题号
        InterviewFlowState lockedFlow = interviewFlowStateMachine.current(ctx.sessionId);
        if (lockedFlow == null) {
            ctx.response.fail("interview flow not initialized");
            return false;
        }
        // （2）题号发生漂移时拒绝本次提交
        String lockedQuestionNumber = interviewFlowStateMachine.currentQuestionNumber(lockedFlow);
        if (!isRequestedQuestionCurrent(ctx.currentQuestionNumber, lockedQuestionNumber)) {
            Metrics.counter("stale_question_reject_total").increment();
            recordAnswerPipelineFailure("stale_question_reject_after_lock");
            ctx.response.fail(STALE_QUESTION_MESSAGE);
            return false;
        }
        String lockedQuestionContent = getQuestionWithReload(ctx.sessionId, lockedQuestionNumber);
        if (StrUtil.isBlank(lockedQuestionContent)) {
            ctx.response.fail("question does not exist or expired");
            return false;
        }
        // （3）回填最新题目内容与追问上下文
        ctx.flowState = lockedFlow;
        ctx.currentQuestionNumber = lockedQuestionNumber;
        ctx.currentQuestion = lockedQuestionContent;
        ctx.currentIsFollowUp = isFollowUpQuestion(lockedQuestionNumber);
        ctx.currentFollowUpCount = resolveFollowUpCount(lockedFlow, lockedQuestionNumber);
        ctx.maxFollowUp = resolveMaxFollowUp(lockedFlow);
        return true;
    }

    /**
     * 调用评分链路得到结构化分数与追问信息，此处仅计算分数，不提交入账。
     */
    private boolean stepEvaluateAndScore(InterviewAnswerPipelineContext ctx) {
        // （1）flow 切换到评估中状态
        interviewFlowStateMachine.moveToEvaluating(ctx.sessionId);

        // （2）解析评分场景绑定的 agent 配置
        AgentPropertiesDO agentProperties = businessAgentResolver.resolveRequired(BusinessAgentScene.INTERVIEW_ANSWER_EVALUATION);
        if (agentProperties == null) {
            recordAnswerPipelineFailure("agent_config_missing");
            ctx.response.fail("agent configuration does not exist");
            return false;
        }

        Map<String, Object> evaluationResult;
        // （3）调用评分服务，AI 门禁异常直接转为业务失败
        try {
            evaluationResult = interviewEvaluationService.evaluateAnswer(
                    ctx.sessionId,
                    ctx.requestId,
                    ctx.currentQuestionNumber,
                    ctx.currentQuestion,
                    ctx.requestParam.getAnswerContent(),
                    agentProperties
            );
        } catch (InterviewAiGuardException guardException) {
            recordAnswerPipelineFailure("ai_guard_" + guardException.getErrorCode().name().toLowerCase());
            ctx.response.fail(guardException.getMessage());
            return false;
        }
        if (evaluationResult == null) {
            recordAnswerPipelineFailure("evaluation_parse_failed");
            ctx.response.fail("failed to parse evaluation result");
            return false;
        }

        Integer score = interviewResponseParser.parseScoreFromResponse(evaluationResult, "score");
        if (score == null) {
            recordAnswerPipelineFailure("evaluation_score_missing");
            ctx.response.fail("score missing in evaluation result");
            return false;
        }

        // （4）解析追问相关字段并写入响应
        ctx.followUpNeeded = interviewResponseParser.asBoolean(evaluationResult.get("follow_up_needed"));
        ctx.followUpQuestion = sanitizeFollowUpQuestion(interviewResponseParser.asString(evaluationResult.get("follow_up_question")));
        ctx.missingPoints = interviewResponseParser.asStringList(evaluationResult.get("missing_points"));

        ctx.score = score;
        ctx.totalScore = interviewQuestionCacheService.getSessionTotalScore(ctx.sessionId);
        ctx.response.withEvaluation(score, interviewResponseParser.asString(evaluationResult.get("feedback")), ctx.totalScore);
        return true;
    }

    /**
     * 推进 flow（追问或下一主问题）并提交分数，提交失败时回滚 flow 快照。
     */
    private boolean stepAdvanceFlowAndAssemble(InterviewAnswerPipelineContext ctx) {
        // （1）先拍快照：后续若计分提交失败，用于补偿回滚 flow，避免“题号推进成功但分数未入账”。
        InterviewFlowState flowSnapshotBeforeAdvance = snapshotFlowState(interviewFlowStateMachine.current(ctx.sessionId));
        InterviewFollowUpRuleDecision ruleDecision = decideFollowUp(ctx);
        int resolvedMaxFollowUp = ruleDecision != null && ruleDecision.getResolvedMaxFollowUp() > 0
                ? ruleDecision.getResolvedMaxFollowUp()
                : ctx.maxFollowUp;
        boolean needFollowUp = ruleDecision != null
                ? ruleDecision.isNeedFollowUp()
                : Boolean.TRUE.equals(ctx.followUpNeeded);

        log.info(
                "Follow-up rule decision, sessionId={}, requestId={}, questionNumber={}, chainId={}, reasonCode={}, reasonText={}, ruleVersion={}, needFollowUp={}, resolvedMaxFollowUp={}, fallback={}",
                ctx.sessionId,
                ctx.requestId,
                ctx.currentQuestionNumber,
                ruleDecision == null ? null : ruleDecision.getChainId(),
                ruleDecision == null ? null : ruleDecision.getReasonCode(),
                ruleDecision == null ? null : ruleDecision.getReasonText(),
                ruleDecision == null ? null : ruleDecision.getRuleVersion(),
                needFollowUp,
                resolvedMaxFollowUp,
                ruleDecision != null && ruleDecision.isFallback()
        );

        // （2）按规则优先走追问分支；追问生成失败则自动回落到主问题推进分支。
        if (needFollowUp && ctx.currentFollowUpCount < resolvedMaxFollowUp) {
            InterviewFollowUpService.FollowUpQuestionResult followUpQuestionResult = interviewFollowUpService.generateFollowUpQuestion(
                    ctx.sessionId,
                    ctx.requestId,
                    ctx.currentQuestionNumber,
                    ctx.currentQuestion,
                    ctx.requestParam.getAnswerContent(),
                    ctx.followUpQuestion,
                    ctx.currentFollowUpCount,
                    resolvedMaxFollowUp
            );
            if (followUpQuestionResult.hasQuestion()) {
                interviewQuestionCacheService.cacheFollowUpQuestion(
                        ctx.sessionId,
                        followUpQuestionResult.getQuestionNumber(),
                        followUpQuestionResult.getQuestionContent()
                );
                InterviewFlowState followUpFlow = interviewFlowStateMachine.startFollowUpQuestion(
                        ctx.sessionId,
                        followUpQuestionResult.getQuestionNumber()
                );
                Integer nextFollowUpCount = followUpFlow != null && followUpFlow.getFollowUpCount() != null
                        ? followUpFlow.getFollowUpCount()
                        : followUpQuestionResult.getFollowUpCount();
                if (!commitScoreAtSuccess(ctx)) {
                    rollbackFlowAfterCommitFailure(ctx, flowSnapshotBeforeAdvance, "followup");
                    return false;
                }
                ctx.response.withNextQuestion(
                        followUpQuestionResult.getQuestionNumber(),
                        followUpQuestionResult.getQuestionContent(),
                        true,
                        nextFollowUpCount
                ).success();
                return true;
            }
        }

        // （3）无追问时推进主问题；到末题则标记完成并返回 finish。
        InterviewFlowState nextFlow = interviewFlowStateMachine.advanceMainQuestion(ctx.sessionId);
        if (nextFlow == null || interviewFlowStateMachine.isCompleted(nextFlow)) {
            interviewFlowStateMachine.markCompleted(ctx.sessionId);
            if (!commitScoreAtSuccess(ctx)) {
                rollbackFlowAfterCommitFailure(ctx, flowSnapshotBeforeAdvance, "finish");
                return false;
            }
            ctx.response.finish().success();
            return true;
        }

        String nextQuestionNumber = interviewFlowStateMachine.currentQuestionNumber(nextFlow);
        String nextQuestion = getQuestionWithReload(ctx.sessionId, nextQuestionNumber);
        if (StrUtil.isBlank(nextQuestion)) {
            ctx.response.fail("next question does not exist or expired");
            return false;
        }

        if (!commitScoreAtSuccess(ctx)) {
            rollbackFlowAfterCommitFailure(ctx, flowSnapshotBeforeAdvance, "next_main");
            return false;
        }
        ctx.response.withNextQuestion(nextQuestionNumber, nextQuestion, false, 0).success();
        return true;
    }

    /**
     * 提交本次得分：追问不计分，仅主问题累加总分；失败返回 false 供调用方回滚 flow。
     */
    private boolean commitScoreAtSuccess(InterviewAnswerPipelineContext ctx) {
        try {
            // 追问不计入总分；仅主问题在“返回成功前”提交入账，避免失败重试重复计分。
            Integer committedTotalScore = Boolean.TRUE.equals(ctx.currentIsFollowUp)
                    ? interviewQuestionCacheService.getSessionTotalScore(ctx.sessionId)
                    : interviewQuestionCacheService.addSessionScore(ctx.sessionId, ctx.score);
            ctx.totalScore = committedTotalScore;
            ctx.response.setTotalScore(committedTotalScore);
            return true;
        } catch (Exception ex) {
            log.error("Failed to commit interview score, sessionId={}, requestId={}", ctx.sessionId, ctx.requestId, ex);
            recordAnswerPipelineFailure("score_commit_failed");
            ctx.response.fail("failed to commit interview score");
            return false;
        }
    }

    /**
     * 分数提交失败时把 flow 恢复到推进前快照，保证客户端重试仍能命中当前题。
     */
    private void rollbackFlowAfterCommitFailure(
            InterviewAnswerPipelineContext ctx,
            InterviewFlowState flowSnapshotBeforeAdvance,
            String branch) {
        if (flowSnapshotBeforeAdvance == null) {
            return;
        }
        try {
            // 分数提交失败时，恢复到推进前状态，保证客户端重试仍能命中当前题。
            interviewQuestionCacheService.restoreInterviewFlow(ctx.sessionId, flowSnapshotBeforeAdvance);
            Metrics.counter("answer_flow_rollback_total", "branch", StrUtil.blankToDefault(branch, "unknown")).increment();
            log.warn("Rolled back interview flow after score commit failure, sessionId={}, requestId={}, branch={}",
                    ctx.sessionId, ctx.requestId, branch);
        } catch (Exception ex) {
            log.error("Failed to rollback interview flow after score commit failure, sessionId={}, requestId={}, branch={}",
                    ctx.sessionId, ctx.requestId, branch, ex);
        }
    }

    /**
     * 深拷贝当前 flow 状态，作为推进前快照用于失败回滚。
     */
    private InterviewFlowState snapshotFlowState(InterviewFlowState state) {
        if (state == null) {
            return null;
        }
        // 手动复制，避免后续对象被原地修改导致“快照”失效。
        InterviewFlowState snapshot = new InterviewFlowState();
        snapshot.setStatus(state.getStatus());
        snapshot.setCurrentIndex(state.getCurrentIndex());
        snapshot.setCurrentQuestionNumber(state.getCurrentQuestionNumber());
        snapshot.setTotalQuestions(state.getTotalQuestions());
        snapshot.setFollowUpCount(state.getFollowUpCount());
        snapshot.setMaxFollowUp(state.getMaxFollowUp());
        snapshot.setVersion(state.getVersion());
        return snapshot;
    }

    /**
     * 组装追问规则上下文并执行规则决策，结果写入流水线上下文。
     */
    private InterviewFollowUpRuleDecision decideFollowUp(InterviewAnswerPipelineContext ctx) {
        // （1）汇总会话、当前题与本次评分信息作为规则输入
        InterviewFollowUpRuleContext ruleContext = new InterviewFollowUpRuleContext();
        ruleContext.setSessionId(ctx.sessionId);
        ruleContext.setRequestId(ctx.requestId);
        ruleContext.setQuestionNumber(ctx.currentQuestionNumber);
        ruleContext.setInterviewType(interviewQuestionCacheService.getSessionInterviewDirection(ctx.sessionId));
        ruleContext.setFollowUpQuestion(Boolean.TRUE.equals(ctx.currentIsFollowUp));
        ruleContext.setFollowUpCount(ctx.currentFollowUpCount == null ? 0 : Math.max(ctx.currentFollowUpCount, 0));
        ruleContext.setMaxFollowUp(ctx.maxFollowUp == null ? 2 : Math.max(ctx.maxFollowUp, 1));
        ruleContext.setScore(ctx.score);
        ruleContext.setFollowUpNeededFromAi(Boolean.TRUE.equals(ctx.followUpNeeded));
        ruleContext.setMissingPoints(ctx.missingPoints);
        ruleContext.setFollowUpQuestionHint(ctx.followUpQuestion);
        ruleContext.setInterviewCompleted(Boolean.TRUE.equals(ctx.response.getFinished()));
        // （2）执行规则决策并缓存结果
        ctx.followUpRuleDecision = interviewFollowUpRuleService.decide(ruleContext);
        return ctx.followUpRuleDecision;
    }

    /**
     * 组装轮次日志并按幂等方式写入，失败返回 false 由调用方转入补偿队列。
     */
    private boolean stepAppendTurnLog(InterviewAnswerPipelineContext ctx) {
        // （1）组装轮次日志，答案超长时截断
        try {
            InterviewTurnLog turn = InterviewTurnLog.builder()
                    .timestamp(System.currentTimeMillis())
                    .requestId(ctx.requestId)
                    .questionNumber(ctx.currentQuestionNumber)
                    .questionContent(ctx.currentQuestion)
                    .answerContent(truncateForLog(ctx.requestParam.getAnswerContent(), 1000))
                    .score(ctx.score)
                    .totalScore(ctx.totalScore)
                    .feedback(ctx.response.getFeedback())
                    .followUpNeeded(ctx.followUpNeeded)
                    .isFollowUp(ctx.currentIsFollowUp)
                    .followUpCount(ctx.currentFollowUpCount)
                    .nextQuestionNumber(ctx.response.getNextQuestionNumber())
                    .nextQuestion(ctx.response.getNextQuestion())
                    .finished(ctx.response.getFinished())
                    .build();
            ctx.turnLog = turn;
            // （2）幂等追加到会话轮次，重复提交不产生重复记录
            return interviewQuestionCacheService.appendInterviewTurnIfAbsent(ctx.sessionId, turn);
        } catch (Exception ex) {
            log.warn("Failed to append interview turn, sessionId: {}", ctx.sessionId, ex);
            return false;
        }
    }

    /**
     * 获取当前 flow；不存在时从缓存或数据库补齐题库并初始化 flow。
     */
    private InterviewFlowState ensureInterviewFlow(String sessionId) {
        // （1）优先复用已存在的 flow
        InterviewFlowState state = interviewFlowStateMachine.current(sessionId);
        if (state != null) {
            return state;
        }

        // （2）题库为空时从数据库回补
        Map<String, String> questions = interviewQuestionCacheService.getSessionInterviewQuestions(sessionId);
        if (questions == null || questions.isEmpty()) {
            interviewQuestionCacheService.loadInterviewQuestionsFromDatabase(sessionId);
            questions = interviewQuestionCacheService.getSessionInterviewQuestions(sessionId);
        }
        if (questions == null || questions.isEmpty()) {
            return null;
        }
        // （3）按题目数量初始化 flow
        return interviewFlowStateMachine.ensureInitialized(sessionId, questions.size());
    }

    /**
     * 按题号读取题干；主问题缓存缺失时从数据库回补后重读。
     */
    private String getQuestionWithReload(String sessionId, String questionNumber) {
        // （1）题号为空直接返回
        if (StrUtil.isBlank(questionNumber)) {
            return null;
        }
        // （2）主问题缓存缺失时回补数据库并重读
        String questionContent = interviewQuestionCacheService.getQuestionByNumber(sessionId, questionNumber);
        if (StrUtil.isBlank(questionContent) && !isFollowUpQuestion(questionNumber)) {
            interviewQuestionCacheService.loadInterviewQuestionsFromDatabase(sessionId);
            questionContent = interviewQuestionCacheService.getQuestionByNumber(sessionId, questionNumber);
        }
        return questionContent;
    }

    /**
     * 判断题号是否为追问题（形如 1-F2）。
     */
    private boolean isFollowUpQuestion(String questionNumber) {
        return StrUtil.isNotBlank(questionNumber) && questionNumber.trim().matches("\\d+-F\\d+");
    }

    /**
     * 解析当前追问题对应的追问次数，题号解析失败时回退 flow 中记录的次数。
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
     * 解析最大追问次数，未配置或非法时默认 2 次。
     */
    private int resolveMaxFollowUp(InterviewFlowState flowState) {
        if (flowState == null || flowState.getMaxFollowUp() == null || flowState.getMaxFollowUp() <= 0) {
            return 2;
        }
        return flowState.getMaxFollowUp();
    }

    /**
     * 从 -F 后缀中解析追问次数，格式非法时返回 0。
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
     * 清洗追问文本，过滤 none/null/N/A/- 等无效占位值。
     */
    private String sanitizeFollowUpQuestion(String question) {
        if (StrUtil.isBlank(question)) {
            return null;
        }
        String normalized = question.trim();
        if ("none".equalsIgnoreCase(normalized)
                || "null".equalsIgnoreCase(normalized)
                || "__FINISH__".equalsIgnoreCase(normalized)
                || "N/A".equalsIgnoreCase(normalized)
                || "-".equals(normalized)) {
            return null;
        }
        return normalized;
    }

    /**
     * 按最大长度截断日志文本，避免日志过长。
     */
    private String truncateForLog(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    /**
     * 记录答题流水线失败指标，按失败原因维度打点。
     */
    private void recordAnswerPipelineFailure(String reason) {
        String safeReason = StrUtil.isBlank(reason) ? "unknown" : reason.trim();
        Metrics.counter("answer_pipeline_fail_total", "reason", safeReason).increment();
    }

    /**
     * 比较请求题号与当前题号是否等价（归一化后比较）。
     */
    private boolean isRequestedQuestionCurrent(String requestedQuestion, String currentQuestion) {
        String normalizedRequested = normalizeQuestionNumber(requestedQuestion);
        String normalizedCurrent = normalizeQuestionNumber(currentQuestion);
        return Objects.equals(normalizedRequested, normalizedCurrent);
    }

    /**
     * 归一化题号：去空格、转大写，并消除数字前导零。
     */
    private String normalizeQuestionNumber(String questionNumber) {
        if (StrUtil.isBlank(questionNumber)) {
            return null;
        }
        String normalized = questionNumber.trim().toUpperCase();
        if (normalized.matches("\\d+")) {
            try {
                return String.valueOf(Integer.parseInt(normalized));
            } catch (Exception ex) {
                return normalized;
            }
        }
        if (normalized.matches("\\d+-F\\d+")) {
            String[] parts = normalized.split("-F");
            try {
                return Integer.parseInt(parts[0]) + "-F" + Integer.parseInt(parts[1]);
            } catch (Exception ex) {
                return normalized;
            }
        }
        return normalized;
    }

    /**
     * 单次答题过程的上下文，承载请求输入、当前题信息、评分结果与幂等/锁状态。
     */
    private static final class InterviewAnswerPipelineContext {
        private String sessionId;                                                // 面试会话ID
        private InterviewAnswerReqDTO requestParam;                            // 请求对象
        private InterviewAnswerRespDTO response;                                // 响应对象
        private String requestId;                                                // 请求ID
        private InterviewFlowState flowState;                                    // 流程状态
        private String currentQuestionNumber;                                    // 当前问题编号
        private String currentQuestion;                                         // 当前问题内容
        private Boolean currentIsFollowUp;                                       // 当前问题是否为追问问题
        private Integer currentFollowUpCount;                                  // 当前主问题的追问次数
        private Integer maxFollowUp;                                             // 最大追问次数
        private Integer score;                                                   // 本次得分
        private Integer totalScore;                                              // 累计总分
        private Boolean followUpNeeded;                                          // 是否需要追问
        private String followUpQuestion;                                         // 追问问题内容
        private List<String> missingPoints;                                      // 缺失点列表
        private InterviewFollowUpRuleDecision followUpRuleDecision;             // 追问规则决策
        private InterviewTurnLog turnLog;                                        // 轮次日志
        private boolean idempotencyStarted;                                     // 是否已开始幂等性处理
        private boolean idempotencyMarkedSucceeded;                             // 是否已标记为幂等性成功
        private RLock questionLock;                                              // 问题锁
    }
}
