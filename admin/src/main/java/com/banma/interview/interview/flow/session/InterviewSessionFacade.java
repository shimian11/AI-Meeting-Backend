package com.banma.interview.interview.flow.session;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.banma.interview.agent.api.io.resp.AgentMessageHistoryRespDTO;
import com.banma.interview.common.convention.exception.ClientException;
import com.banma.interview.common.enums.InterviewErrorCodeEnum;
import com.banma.interview.interview.api.io.req.DemeanorEvaluationReqDTO;
import com.banma.interview.interview.api.io.req.InterviewAnswerReqDTO;
import com.banma.interview.interview.api.io.req.InterviewConversationPageReqDTO;
import com.banma.interview.interview.api.io.req.InterviewQuestionReqDTO;
import com.banma.interview.interview.api.io.resp.InterviewAnswerRespDTO;
import com.banma.interview.interview.api.io.resp.InterviewConversationRespDTO;
import com.banma.interview.interview.api.io.resp.InterviewQuestionRespDTO;
import com.banma.interview.interview.api.io.resp.InterviewRecordRespDTO;
import com.banma.interview.interview.api.io.resp.InterviewSessionCreateRespDTO;
import com.banma.interview.interview.api.io.resp.InterviewSessionRestoreRespDTO;
import com.banma.interview.interview.api.io.resp.RadarChartDTO;
import com.banma.interview.interview.application.InterviewWorkflowService;
import com.banma.interview.interview.application.runtime.InterviewSessionRuntimeRehydrateService;
import com.banma.interview.interview.application.runtime.InterviewRuntimeRehydrateScope;
import com.banma.interview.interview.application.runtime.InterviewSessionRuntimeSnapshotService;
import com.banma.interview.interview.dao.entity.InterviewQuestion;
import com.banma.interview.interview.dao.entity.InterviewSession;
import com.banma.interview.interview.flow.report.InterviewResumePreviewService;
import com.banma.interview.interview.service.InterviewQuestionCacheService;
import com.banma.interview.interview.service.InterviewQuestionService;
import com.banma.interview.interview.service.InterviewRecordService;
import com.banma.interview.interview.service.InterviewSessionService;
import com.banma.interview.interview.service.model.InterviewRuntimeLoadMode;
import com.banma.interview.interview.service.model.InterviewSessionStatus;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * 面试会话门面：对上层暴露会话创建、题目提取、答题、神态评估与报告数据读取等统一入口。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class InterviewSessionFacade {

    /** 面试工作流编排（提取、落库、状态推进） */
    private final InterviewWorkflowService interviewWorkflowService;
    /** 题目、流程与得分的 Redis 缓存读写 */
    private final InterviewQuestionCacheService interviewQuestionCacheService;
    /** 面试题目持久化服务（MySQL） */
    private final InterviewQuestionService interviewQuestionService;
    /** 面试记录服务 */
    private final InterviewRecordService interviewRecordService;
    /** 简历预览下载服务 */
    private final InterviewResumePreviewService interviewResumePreviewService;
    /** 面试会话持久化服务（Mongo） */
    private final InterviewSessionService interviewSessionService;
    /** 运行态快照读写与刷新触发 */
    private final InterviewSessionRuntimeSnapshotService runtimeSnapshotService;
    /** 运行态热冷快照重水化 */
    private final InterviewSessionRuntimeRehydrateService runtimeRehydrateService;

    /**
     * 创建面试会话，直接委托会话服务完成初始化。
     */
    public InterviewSessionCreateRespDTO createSession(Long userId) {
        return interviewSessionService.createSession(userId);
    }

    /**
     * 分页查询当前用户的面试会话列表。
     */
    public IPage<InterviewConversationRespDTO> pageConversations(Long userId, InterviewConversationPageReqDTO requestParam) {
        return interviewSessionService.pageConversations(userId, requestParam);
    }

    /**
     * 获取会话聊天历史：校验归属后提示该能力尚未开放。
     */
    public List<AgentMessageHistoryRespDTO> getConversationHistory(String sessionId, Long userId) {
        interviewSessionService.requireOwnedSession(sessionId, userId);
        throw new ClientException("interview history is not enabled", InterviewErrorCodeEnum.INTERVIEW_SESSION_INVALID_STATE);
    }

    /**
     * 分页获取会话消息历史：校验归属后提示该能力尚未开放。
     */
    public IPage<AgentMessageHistoryRespDTO> pageHistoryMessages(
            String sessionId,
            Integer current,
            Integer size,
            Long userId) {
        if (StrUtil.isNotBlank(sessionId)) {
            interviewSessionService.requireOwnedSession(sessionId, userId);
        }
        throw new ClientException("interview history paging is not enabled", InterviewErrorCodeEnum.INTERVIEW_SESSION_INVALID_STATE);
    }

    /**
     * 结束面试会话：统一走记录收口流程，落记录并补齐结束态。
     */
    public void finishSession(String sessionId, Long userId) {
        // （1）统一走 finalize 收口：落记录 + 补齐会话结束态（内部含锁与重试）。
        interviewRecordService.saveInterviewRecordFromRedis(sessionId, userId);
    }

    /**
     * 结束对话：与结束会话行为一致。
     */
    public void endConversation(String sessionId, Long userId) {
        finishSession(sessionId, userId);
    }

    /**
     * 从简历提取面试题目：先标记上传中，提取成功后推进 READY，失败回落到 DRAFT。
     */
    public InterviewQuestionRespDTO extractInterviewQuestions(
            String sessionId,
            MultipartFile resumePdf,
            Long userId,
            String username) {
        // （1）进入提取前先把会话打到“上传中”，避免并发接口误判状态。
        interviewSessionService.markResumeUploading(sessionId, userId);

        // （2）组装提取请求并委托 workflow（内部会做上传、AI 调用、结构化落库）。
        InterviewQuestionReqDTO reqDTO = new InterviewQuestionReqDTO();
        reqDTO.setUserName(username);
        reqDTO.setSessionId(sessionId);
        reqDTO.setResumePdf(resumePdf);

        InterviewQuestionRespDTO response = interviewWorkflowService.extractInterviewQuestions(reqDTO);
        if (response != null && Integer.valueOf(1).equals(response.getIsSuccess())) {
            // （3）提取成功后推进到 READY，并同步会话级简历地址与面试方向。
            interviewSessionService.markReady(
                    sessionId,
                    userId,
                    response.getResumeFileUrl(),
                    response.getInterviewType()
            );
            runtimeSnapshotService.refreshAfterQuestionExtraction(sessionId);
            return response;
        }

        // （3）提取失败回落到 DRAFT，保留后续重试入口。
        interviewSessionService.markDraft(sessionId, userId);
        return response;
    }

    /**
     * 提交答题：校验会话可继续后，视情况推进状态并委托答题编排流水线。
     */
    public InterviewAnswerRespDTO answerInterviewQuestion(
            String sessionId,
            InterviewAnswerReqDTO requestParam,
            Long userId) {
        // （1）先校验会话可继续（归属 + 状态）。
        ensureInterviewCanProceed(sessionId, userId);
        // （2）首次答题把 READY 提升到 IN_PROGRESS。
        interviewSessionService.markInProgressIfReady(sessionId, userId);
        // （3）委托答题编排流水线，内部处理幂等/加锁/评估/推进。
        requestParam.setSessionId(sessionId);
        return interviewWorkflowService.answerInterviewQuestion(sessionId, requestParam);
    }

    /**
     * 获取下一题：校验会话可继续，必要时推进为进行中后返回当前题。
     */
    public InterviewAnswerRespDTO getNextQuestion(String sessionId, Long userId) {
        ensureInterviewCanProceed(sessionId, userId);
        interviewSessionService.markInProgressIfReady(sessionId, userId);
        return interviewWorkflowService.getNextQuestion(sessionId);
    }

    /**
     * 获取当前题：校验会话可继续，未结束的成功响应会顺带推进为进行中状态。
     */
    public InterviewAnswerRespDTO getCurrentQuestion(String sessionId, Long userId) {
        ensureInterviewCanProceed(sessionId, userId);
        InterviewAnswerRespDTO response = interviewWorkflowService.getCurrentQuestion(sessionId);
        if (response != null && Boolean.TRUE.equals(response.getIsSuccess()) && !Boolean.TRUE.equals(response.getFinished())) {
            interviewSessionService.markInProgressIfReady(sessionId, userId);
        }
        return response;
    }

    /**
     * 加载简历预览：校验会话归属后读取并返回简历文件内容。
     */
    public InterviewResumePreviewService.ResumePreviewResource loadResumePreview(String sessionId, Long userId) {
        interviewSessionService.requireOwnedSession(sessionId, userId);
        return interviewResumePreviewService.loadResumePreview(sessionId);
    }

    /**
     * 恢复面试会话：汇总会话主信息、题库字段与缓存态数据，供前端恢复页直接渲染。
     */
    public InterviewSessionRestoreRespDTO restoreInterviewSession(String sessionId, Long userId) {
        // （1）先恢复会话主信息（状态、简历、方向等主字段）。
        InterviewSession session = interviewSessionService.requireOwnedSession(sessionId, userId);
        runtimeRehydrateService.ensureRuntime(sessionId, InterviewRuntimeLoadMode.READ_ONLY, InterviewRuntimeRehydrateScope.MATERIAL_ONLY);

        InterviewSessionRestoreRespDTO response = new InterviewSessionRestoreRespDTO();
        response.setSessionId(sessionId);
        response.setStatus(session.getStatus());
        response.setCanResume(isSessionResumable(session));
        response.setResumeFileUrl(session.getResumeFileUrl());
        response.setInterviewType(session.getInterviewType());

        // （2）再用 question 表补齐 resume/interviewType/resumeScore，降低对缓存依赖。
        InterviewQuestion question = interviewQuestionService.getBySessionId(sessionId);
        if (question != null) {
            if (StrUtil.isBlank(response.getResumeFileUrl())) {
                response.setResumeFileUrl(question.getResumeFileUrl());
            }
            if (StrUtil.isBlank(response.getInterviewType())) {
                response.setInterviewType(question.getInterviewType());
            }
            response.setResumeScore(question.getResumeScore());
        }

        // （3）最后回补缓存态字段（suggestions、resumeScore），保证前端恢复页可直接渲染。
        Map<String, String> suggestions = interviewQuestionCacheService.getSessionInterviewSuggestions(sessionId);
        if (suggestions == null || suggestions.isEmpty()) {
            interviewQuestionCacheService.loadInterviewSuggestionsFromDatabase(sessionId);
            suggestions = interviewQuestionCacheService.getSessionInterviewSuggestions(sessionId);
        }
        response.setSuggestions(suggestions);

        Integer resumeScore = interviewQuestionCacheService.getSessionResumeScore(sessionId);
        if (resumeScore == null) {
            interviewQuestionCacheService.loadResumeScoreFromDatabase(sessionId);
            resumeScore = interviewQuestionCacheService.getSessionResumeScore(sessionId);
        }
        if (resumeScore != null) {
            response.setResumeScore(resumeScore);
        }

        if (StrUtil.isBlank(response.getInterviewType()) && question != null) {
            response.setInterviewType(question.getInterviewType());
        }
        return response;
    }

    /**
     * 读取会话题库，缓存为空时从数据库回补。
     */
    public Map<String, String> getSessionInterviewQuestions(String sessionId, Long userId) {
        interviewSessionService.requireOwnedSession(sessionId, userId);
        runtimeRehydrateService.ensureRuntime(sessionId, InterviewRuntimeLoadMode.READ_ONLY, InterviewRuntimeRehydrateScope.MATERIAL_ONLY);

        Map<String, String> questions = interviewQuestionCacheService.getSessionInterviewQuestions(sessionId);
        if (questions == null || questions.isEmpty()) {
            interviewQuestionCacheService.loadInterviewQuestionsFromDatabase(sessionId);
            questions = interviewQuestionCacheService.getSessionInterviewQuestions(sessionId);
        }
        return questions;
    }

    /**
     * 读取会话总分：缓存优先，缓存无有效分时回退到面试记录中的分数。
     */
    public Integer getSessionTotalScore(String sessionId, Long userId) {
        interviewSessionService.requireOwnedSession(sessionId, userId);
        runtimeRehydrateService.ensureRuntime(sessionId, InterviewRuntimeLoadMode.READ_ONLY, InterviewRuntimeRehydrateScope.SCORE_ONLY);
        // 分数读取顺序：缓存 > 记录快照，避免缓存丢失导致分数回退。
        Integer score = interviewQuestionCacheService.getSessionTotalScore(sessionId);
        if (score != null && score > 0) {
            return score;
        }
        InterviewRecordRespDTO record = interviewRecordService.getBySessionId(sessionId, userId);
        if (record != null && record.getInterviewScore() != null) {
            return record.getInterviewScore();
        }
        return score;
    }

    /**
     * 读取会话面试建议，缓存为空时从数据库回补。
     */
    public Map<String, String> getSessionInterviewSuggestions(String sessionId, Long userId) {
        interviewSessionService.requireOwnedSession(sessionId, userId);
        runtimeRehydrateService.ensureRuntime(sessionId, InterviewRuntimeLoadMode.READ_ONLY, InterviewRuntimeRehydrateScope.MATERIAL_ONLY);

        Map<String, String> suggestions = interviewQuestionCacheService.getSessionInterviewSuggestions(sessionId);
        if (suggestions == null || suggestions.isEmpty()) {
            interviewQuestionCacheService.loadInterviewSuggestionsFromDatabase(sessionId);
            suggestions = interviewQuestionCacheService.getSessionInterviewSuggestions(sessionId);
        }
        return suggestions;
    }

    /**
     * 读取会话简历评分，缓存为空时从数据库回补。
     */
    public Integer getSessionResumeScore(String sessionId, Long userId) {
        interviewSessionService.requireOwnedSession(sessionId, userId);
        runtimeRehydrateService.ensureRuntime(sessionId, InterviewRuntimeLoadMode.READ_ONLY, InterviewRuntimeRehydrateScope.MATERIAL_ONLY);

        Integer resumeScore = interviewQuestionCacheService.getSessionResumeScore(sessionId);
        if (resumeScore == null) {
            interviewQuestionCacheService.loadResumeScoreFromDatabase(sessionId);
            resumeScore = interviewQuestionCacheService.getSessionResumeScore(sessionId);
        }
        return resumeScore;
    }

    /**
     * 读取雷达图数据：缓存有效则直接返回，否则回退到面试记录中的快照数据。
     */
    public RadarChartDTO getRadarChartData(String sessionId, Long userId) {
        interviewSessionService.requireOwnedSession(sessionId, userId);
        runtimeRehydrateService.ensureRuntime(sessionId, InterviewRuntimeLoadMode.READ_ONLY, InterviewRuntimeRehydrateScope.FULL_RUNTIME);
        RadarChartDTO radar = interviewQuestionCacheService.getRadarChartData(sessionId);
        if (hasRadarSignal(radar)) {
            return radar;
        }
        InterviewRecordRespDTO record = interviewRecordService.getBySessionId(sessionId, userId);
        if (record != null && record.getRadarChart() != null) {
            return record.getRadarChart();
        }
        return radar;
    }

    /**
     * 执行神态评估：校验会话可继续与路径/参数中的 sessionId 一致后委托工作流。
     */
    public String evaluateDemeanor(
            String sessionId,
            MultipartFile userPhoto,
            String requestSessionId,
            Long userId,
            String username) {
        // （1）神态评估只允许在可继续的会话上执行。
        ensureInterviewCanProceed(sessionId, userId);
        if (requestSessionId != null && !sessionId.equals(requestSessionId)) {
            throw new ClientException("sessionId mismatch between path and request parameter");
        }

        // （2）组装请求并委托 workflow（内部处理上传、AI 评估与分值落缓存）。
        DemeanorEvaluationReqDTO reqDTO = new DemeanorEvaluationReqDTO();
        reqDTO.setUserName(username);
        reqDTO.setSessionId(sessionId);
        reqDTO.setUserPhoto(userPhoto);
        return interviewWorkflowService.evaluateDemeanor(reqDTO);
    }

    /**
     * 校验会话可继续：要求会话归属当前用户且状态允许续答。
     */
    private void ensureInterviewCanProceed(String sessionId, Long userId) {
        InterviewSession session = interviewSessionService.requireOwnedSession(sessionId, userId);
        if (session == null || !isSessionResumable(session)) {
            throw new ClientException(InterviewErrorCodeEnum.INTERVIEW_SESSION_INVALID_STATE);
        }
    }

    /**
     * 判断会话状态是否允许续答，未知状态按不可续答处理。
     */
    private boolean isSessionResumable(InterviewSession session) {
        if (session == null || StrUtil.isBlank(session.getStatus())) {
            return false;
        }
        try {
            return InterviewSessionStatus.valueOf(session.getStatus()).canResume();
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * 判断雷达图是否存在任一大于 0 的维度分值。
     */
    private boolean hasRadarSignal(RadarChartDTO radar) {
        if (radar == null) {
            return false;
        }
        return positive(radar.getResumeScore())
                || positive(radar.getInterviewPerformance())
                || positive(radar.getDemeanorEvaluation())
                || positive(radar.getProfessionalSkills())
                || positive(radar.getPotentialIndex());
    }

    /**
     * 判断分值是否为正数。
     */
    private boolean positive(Integer value) {
        return value != null && value > 0;
    }
}
