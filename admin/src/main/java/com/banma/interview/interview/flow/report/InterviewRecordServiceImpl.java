package com.banma.interview.interview.flow.report;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.banma.interview.common.convention.exception.ClientException;
import com.banma.interview.common.enums.InterviewErrorCodeEnum;
import com.banma.interview.interview.application.finalize.InterviewFinalizeLockService;
import com.banma.interview.interview.application.InterviewSessionOwnershipService;
import com.banma.interview.interview.application.runtime.InterviewSessionRuntimeRehydrateService;
import com.banma.interview.interview.application.runtime.InterviewSessionRuntimeSnapshotService;
import com.banma.interview.interview.application.runtime.InterviewRuntimeRehydrateScope;
import com.banma.interview.interview.api.io.req.InterviewRecordPageReqDTO;
import com.banma.interview.interview.api.io.req.InterviewRecordSaveReqDTO;
import com.banma.interview.interview.api.io.resp.InterviewPlaybackItemRespDTO;
import com.banma.interview.interview.api.io.resp.InterviewRecordRespDTO;
import com.banma.interview.interview.api.io.resp.InterviewReviewFeedbackRespDTO;
import com.banma.interview.interview.api.io.resp.RadarChartDTO;
import com.banma.interview.interview.api.io.resp.RadarDimensionItemRespDTO;
import com.banma.interview.interview.dao.entity.InterviewQuestion;
import com.banma.interview.interview.dao.entity.InterviewSession;
import com.banma.interview.interview.dao.entity.InterviewRecordDO;
import com.banma.interview.interview.dao.mapper.InterviewRecordMapper;
import com.banma.interview.interview.service.InterviewQuestionCacheService;
import com.banma.interview.interview.service.InterviewQuestionService;
import com.banma.interview.interview.service.InterviewRecordService;
import com.banma.interview.interview.service.InterviewSessionService;
import com.banma.interview.interview.service.model.InterviewRuntimeLoadMode;
import com.banma.interview.interview.service.model.InterviewSessionStatus;
import com.banma.interview.interview.service.model.InterviewTurnLog;
import io.micrometer.core.instrument.Metrics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.springframework.beans.BeanUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Interview record service implementation.
 * 面试记录服务实现：负责记录落库、分页查询、报告字段补齐与最终收口。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InterviewRecordServiceImpl extends ServiceImpl<InterviewRecordMapper, InterviewRecordDO> implements InterviewRecordService {

    /** 收口阶段最大重试次数 */
    private static final int MAX_FINALIZE_RETRIES = 3;

    /** 题目、流程与得分的 Redis 缓存读写 */
    private final InterviewQuestionCacheService interviewQuestionCacheService;
    /** 会话归属校验（防越权） */
    private final InterviewSessionOwnershipService interviewSessionOwnershipService;
    /** 面试会话持久化服务（Mongo） */
    private final InterviewSessionService interviewSessionService;
    /** 面试题目持久化服务（MySQL） */
    private final InterviewQuestionService interviewQuestionService;
    /** 收口阶段分布式锁 */
    private final InterviewFinalizeLockService interviewFinalizeLockService;
    /** 运行态快照读写与刷新触发 */
    private final InterviewSessionRuntimeSnapshotService runtimeSnapshotService;
    /** 运行态热冷快照重水化 */
    private final InterviewSessionRuntimeRehydrateService runtimeRehydrateService;

    /**
     * 保存面试记录：回补分数、建议、题量等字段并写入记录表，支持并发下的插入转更新。
     */
    @Override
    public void saveInterviewRecord(String sessionId, Long userId, InterviewRecordSaveReqDTO requestParam) {
        // （1）校验会话与用户，并确保运行时已恢复
        if (StrUtil.isBlank(sessionId)) {
            throw new ClientException(InterviewErrorCodeEnum.SESSION_ID_EMPTY);
        }
        validateUserId(userId);
        runtimeRehydrateService.ensureRuntime(sessionId, InterviewRuntimeLoadMode.READ_ONLY, InterviewRuntimeRehydrateScope.FULL_RUNTIME);

        // （2）查询会话归属、题库与既有记录
        InterviewRecordSaveReqDTO safeRequest = requestParam == null ? new InterviewRecordSaveReqDTO() : requestParam;
        InterviewSession session = interviewSessionOwnershipService.requireOwnedSession(sessionId, userId);
        InterviewQuestion question = interviewQuestionService.getBySessionId(sessionId);

        LambdaQueryWrapper<InterviewRecordDO> queryWrapper = Wrappers.lambdaQuery(InterviewRecordDO.class)
                .eq(InterviewRecordDO::getUserId, userId)
                .eq(InterviewRecordDO::getSessionId, sessionId)
                .eq(InterviewRecordDO::getDelFlag, 0);
        InterviewRecordDO record = baseMapper.selectOne(queryWrapper);

        // 分数采用“多级回补”：请求值 > 缓存 > 既有记录 > turns推导 > 兜底，避免缓存丢失把历史分数覆盖为0。
        // （3）回补分数、建议、简历评分、题量与面试方向
        Integer totalScore = resolveInterviewScore(sessionId, safeRequest, record);
        String suggestions = resolveInterviewSuggestions(sessionId, safeRequest, question);
        Integer resumeScore = resolveResumeScore(sessionId, question);
        Integer questionCount = resolveQuestionCount(sessionId, question);
        String interviewDirection = resolveInterviewDirection(sessionId, safeRequest, session, question);

        // （4）组装记录字段：时间、时长、状态与快照
        Date now = new Date();
        if (record == null) {
            record = new InterviewRecordDO();
            record.setUserId(userId);
            record.setSessionId(sessionId);
            record.setCreateTime(now);
            record.setDelFlag(0);
        }

        Date sessionStartTime = session.getStartTime() != null
                ? session.getStartTime()
                : (session.getCreateTime() != null ? session.getCreateTime() : now);
        Date startTime = record.getStartTime() != null ? record.getStartTime() : sessionStartTime;
        Integer durationSeconds = calculateDurationSeconds(startTime, now);
        String status = resolveInterviewStatus(session.getStatus(), totalScore);
        String snapshotJson = buildSessionSnapshotJson(
                session,
                resumeScore,
                totalScore,
                questionCount,
                interviewDirection,
                status,
                suggestions
        );

        record.setInterviewScore(totalScore);
        record.setResumeScore(resumeScore);
        record.setInterviewStatus(status);
        record.setQuestionCount(questionCount);
        record.setInterviewerAgentId(session.getInterviewerAgentId());
        record.setInterviewSuggestions(suggestions);
        if (StrUtil.isNotBlank(interviewDirection)) {
            record.setInterviewDirection(interviewDirection);
        }
        record.setStartTime(startTime);
        // 只在会话已结束时写 endTime，避免中间态记录被误标记为“已结束”。
        if (InterviewSessionStatus.FINISHED.name().equalsIgnoreCase(status)) {
            record.setEndTime(now);
        }
        record.setDurationSeconds(durationSeconds);
        record.setSessionSnapshotJson(snapshotJson);
        record.setUpdateTime(now);

        // （5）无主键则插入，唯一键冲突时按既有记录转更新
        if (record.getId() == null) {
            try {
                baseMapper.insert(record);
                log.info("Created interview record, userId={}, sessionId={}", userId, sessionId);
            } catch (DuplicateKeyException duplicateKeyException) {
                InterviewRecordDO existingRecord = baseMapper.selectOne(queryWrapper);
                if (existingRecord == null) {
                    throw duplicateKeyException;
                }
                record.setId(existingRecord.getId());
                if (record.getCreateTime() == null) {
                    record.setCreateTime(existingRecord.getCreateTime());
                }
                baseMapper.updateById(record);
                log.info("Recovered duplicate interview record insert by update, userId={}, sessionId={}", userId, sessionId);
            }
        } else {
            baseMapper.updateById(record);
            log.info("Updated interview record, userId={}, sessionId={}", userId, sessionId);
        }
        // （6）刷新会话运行时快照
        runtimeSnapshotService.refreshAfterFinalize(sessionId);
    }

    /**
     * 分页查询当前用户的面试记录，支持会话、分数区间与方向过滤，并回填建议映射。
     */
    @Override
    public IPage<InterviewRecordRespDTO> pageInterviewRecords(Long userId, InterviewRecordPageReqDTO requestParam) {
        // （1）校验用户并构建分页查询条件
        validateUserId(userId);

        Page<InterviewRecordDO> page = new Page<>(requestParam.getPageNum(), requestParam.getPageSize());
        LambdaQueryWrapper<InterviewRecordDO> queryWrapper = Wrappers.lambdaQuery(InterviewRecordDO.class)
                .eq(InterviewRecordDO::getUserId, userId)
                .eq(InterviewRecordDO::getDelFlag, 0);

        // （2）叠加会话、分数区间与面试方向过滤
        if (StringUtils.hasText(requestParam.getSessionId())) {
            queryWrapper.eq(InterviewRecordDO::getSessionId, requestParam.getSessionId());
        }
        if (requestParam.getMinScore() != null) {
            queryWrapper.ge(InterviewRecordDO::getInterviewScore, requestParam.getMinScore());
        }
        if (requestParam.getMaxScore() != null) {
            queryWrapper.le(InterviewRecordDO::getInterviewScore, requestParam.getMaxScore());
        }
        if (StringUtils.hasText(requestParam.getInterviewDirection())) {
            queryWrapper.eq(InterviewRecordDO::getInterviewDirection, requestParam.getInterviewDirection());
        }
        queryWrapper.orderByDesc(InterviewRecordDO::getCreateTime);

        Page<InterviewRecordDO> recordPage = baseMapper.selectPage(page, queryWrapper);
        // （3）转换记录并解析面试建议映射
        List<InterviewRecordRespDTO> resultList = recordPage.getRecords().stream()
                .map(record -> {
                    InterviewRecordRespDTO respDTO = new InterviewRecordRespDTO();
                    BeanUtils.copyProperties(record, respDTO);
                    if (StrUtil.isNotBlank(record.getInterviewSuggestions())) {
                        respDTO.setInterviewSuggestionsMap(parseInterviewSuggestions(record.getInterviewSuggestions()));
                    }
                    return respDTO;
                })
                .collect(Collectors.toList());

        Page<InterviewRecordRespDTO> resultPage = new Page<>(recordPage.getCurrent(), recordPage.getSize(), recordPage.getTotal());
        resultPage.setRecords(resultList);
        return resultPage;
    }

    /**
     * 按会话查询面试记录详情；记录不存在时按缓存自动补建，并补齐报告展示字段。
     */
    @Override
    public InterviewRecordRespDTO getBySessionId(String sessionId, Long userId) {
        // （1）校验参数并按用户与会话查询记录
        if (StrUtil.isBlank(sessionId)) {
            throw new ClientException(InterviewErrorCodeEnum.SESSION_ID_EMPTY);
        }
        validateUserId(userId);

        LambdaQueryWrapper<InterviewRecordDO> queryWrapper = Wrappers.lambdaQuery(InterviewRecordDO.class)
                .eq(InterviewRecordDO::getUserId, userId)
                .eq(InterviewRecordDO::getSessionId, sessionId)
                .eq(InterviewRecordDO::getDelFlag, 0);
        InterviewRecordDO record = baseMapper.selectOne(queryWrapper);
        if (record == null) {
            // Auto-create a record from cache when report page is opened for the first time.
            // （2）首次打开报告页时按缓存自动补建记录
            try {
                saveInterviewRecord(sessionId, userId, new InterviewRecordSaveReqDTO());
                record = baseMapper.selectOne(queryWrapper);
            } catch (Exception ex) {
                log.warn("Auto-create interview record failed, sessionId={}, userId={}", sessionId, userId, ex);
            }
        }
        if (record == null) {
            return null;
        }

        InterviewRecordRespDTO respDTO = new InterviewRecordRespDTO();
        BeanUtils.copyProperties(record, respDTO);
        if (StrUtil.isNotBlank(record.getInterviewSuggestions())) {
            respDTO.setInterviewSuggestionsMap(parseInterviewSuggestions(record.getInterviewSuggestions()));
        }
        // （3）补齐雷达图、回放与复盘反馈等报告字段
        enrichReportFields(sessionId, record, respDTO);
        return respDTO;
    }

    /**
     * 从缓存/快照收口面试记录：加 finalize 锁后保存快照、结束会话并再次保存，带有限次重试。
     */
    @Override
    public void saveInterviewRecordFromRedis(String sessionId, Long userId) {
        if (StrUtil.isBlank(sessionId)) {
            throw new ClientException(InterviewErrorCodeEnum.SESSION_ID_EMPTY);
        }
        validateUserId(userId);
        RLock finalizeLock = null;
        try {
            // finalize 锁保证同一 session 同时只有一个收口流程在跑，避免 finish/save 并发互相覆盖。
            finalizeLock = interviewFinalizeLockService.acquire(sessionId);
            if (finalizeLock == null) {
                Metrics.counter("finalize_lock_contention_total").increment();
                throw new ClientException("finalize is processing, please retry");
            }
            // （2）最多重试 MAX_FINALIZE_RETRIES 次，失败按次递增指标
            for (int attempt = 1; attempt <= MAX_FINALIZE_RETRIES; attempt++) {
                try {
                    InterviewSession session = interviewSessionOwnershipService.requireOwnedSession(sessionId, userId);
                    boolean alreadyFinished = session != null
                            && InterviewSessionStatus.FINISHED.name().equalsIgnoreCase(session.getStatus());

                    // 先保存一次快照；若会话尚未结束，再 finish 后二次保存，确保最终状态和报告字段一致。
                    saveInterviewRecord(sessionId, userId, new InterviewRecordSaveReqDTO());
                    if (!alreadyFinished) {
                        interviewSessionService.finishSession(sessionId, userId);
                        saveInterviewRecord(sessionId, userId, new InterviewRecordSaveReqDTO());
                    }
                    log.info("Saved interview record from redis, sessionId={}, userId={}, attempt={}",
                            sessionId, userId, attempt);
                    return;
                } catch (Exception ex) {
                    Metrics.counter("finalize_retry_total").increment();
                    log.warn("Finalize attempt failed, sessionId={}, userId={}, attempt={}",
                            sessionId, userId, attempt, ex);
                    if (attempt >= MAX_FINALIZE_RETRIES) {
                        throw ex;
                    }
                }
            }
        } catch (ClientException clientException) {
            throw clientException;
        } catch (Exception ex) {
            throw new ClientException("failed to finalize interview record, please retry");
        } finally {
            interviewFinalizeLockService.release(finalizeLock);
        }
    }

    /**
     * 校验用户 ID 是否有效，无效时抛出业务异常。
     */
    private void validateUserId(Long userId) {
        if (userId == null || userId <= 0) {
            throw new ClientException(InterviewErrorCodeEnum.INVALID_USER_ID);
        }
    }

    /**
     * 解析面试总分：请求值 > 缓存分 > 既有记录 > turns 推导 > 兜底 0。
     */
    private Integer resolveInterviewScore(
            String sessionId,
            InterviewRecordSaveReqDTO requestParam,
            InterviewRecordDO existingRecord) {
        if (requestParam.getInterviewScore() != null) {
            return requestParam.getInterviewScore();
        }
        Integer score = interviewQuestionCacheService.getSessionTotalScore(sessionId);
        if (score != null && score > 0) {
            return score;
        }
        if (existingRecord != null && existingRecord.getInterviewScore() != null) {
            return existingRecord.getInterviewScore();
        }
        Integer derivedScore = deriveScoreFromTurns(sessionId);
        if (derivedScore != null) {
            return derivedScore;
        }
        return score != null ? score : 0;
    }

    /**
     * 从已持久化的轮次日志推导平均分，仅统计主问题，无有效轮次时返回 null。
     */
    private Integer deriveScoreFromTurns(String sessionId) {
        List<InterviewTurnLog> turns = runtimeSnapshotService.loadPersistedTurns(sessionId);
        if (turns == null || turns.isEmpty()) {
            return null;
        }
        int sum = 0;
        int count = 0;
        for (InterviewTurnLog turn : turns) {
            if (turn == null || turn.getScore() == null || Boolean.TRUE.equals(turn.getIsFollowUp())) {
                continue;
            }
            int score = clampScore(turn.getScore());
            sum += score;
            count++;
        }
        if (count <= 0) {
            return null;
        }
        return clampScore((int) Math.round((double) sum / count));
    }

    /**
     * 解析简历评分：优先取缓存，其次取题库记录。
     */
    private Integer resolveResumeScore(String sessionId, InterviewQuestion question) {
        Integer resumeScore = interviewQuestionCacheService.getSessionResumeScore(sessionId);
        if (resumeScore != null) {
            return resumeScore;
        }
        if (question != null && question.getResumeScore() != null) {
            return question.getResumeScore();
        }
        return null;
    }

    /**
     * 解析题目数量：优先取缓存题库，其次取题库记录中的 JSON/列表。
     */
    private Integer resolveQuestionCount(String sessionId, InterviewQuestion question) {
        Map<String, String> interviewQuestions = interviewQuestionCacheService.getSessionInterviewQuestions(sessionId);
        if (interviewQuestions != null && !interviewQuestions.isEmpty()) {
            return interviewQuestions.size();
        }
        Map<String, String> questionsFromDb = resolveQuestionsMapFromQuestion(question);
        return questionsFromDb == null ? 0 : questionsFromDb.size();
    }

    /**
     * 解析面试建议：请求值优先，其次缓存，最后从题库记录回补后按序号排序拼接。
     */
    private String resolveInterviewSuggestions(
            String sessionId,
            InterviewRecordSaveReqDTO requestParam,
            InterviewQuestion question) {
        if (StrUtil.isNotBlank(requestParam.getInterviewSuggestions())) {
            return requestParam.getInterviewSuggestions();
        }
        Map<String, String> suggestionsMap = interviewQuestionCacheService.getSessionInterviewSuggestions(sessionId);
        if ((suggestionsMap == null || suggestionsMap.isEmpty()) && question != null) {
            suggestionsMap = resolveSuggestionsMapFromQuestion(question);
        }
        if (suggestionsMap == null || suggestionsMap.isEmpty()) {
            return null;
        }
        return sortAndJoinValues(suggestionsMap);
    }

    /**
     * 解析面试方向：请求 > 缓存 > 会话 interviewType > 题库 interviewType。
     */
    private String resolveInterviewDirection(
            String sessionId,
            InterviewRecordSaveReqDTO requestParam,
            InterviewSession session,
            InterviewQuestion question) {
        // 方向字段按“请求 > 缓存 > session.interviewType > question.interviewType”回补，优先使用最新语义。
        if (requestParam != null && StrUtil.isNotBlank(requestParam.getInterviewDirection())) {
            return requestParam.getInterviewDirection().trim();
        }

        String directionFromCache = interviewQuestionCacheService.getSessionInterviewDirection(sessionId);
        if (StrUtil.isNotBlank(directionFromCache)) {
            return directionFromCache.trim();
        }

        if (session != null && StrUtil.isNotBlank(session.getInterviewType())) {
            return session.getInterviewType().trim();
        }
        if (question != null && StrUtil.isNotBlank(question.getInterviewType())) {
            return question.getInterviewType().trim();
        }
        return null;
    }

    /**
     * 从题库记录解析建议映射，优先使用 JSON 字段，其次使用列表字段。
     */
    private Map<String, String> resolveSuggestionsMapFromQuestion(InterviewQuestion question) {
        if (question == null) {
            return Collections.emptyMap();
        }
        Map<String, String> fromJson = parseIndexedMap(question.getSuggestionsJson());
        if (!fromJson.isEmpty()) {
            return fromJson;
        }
        return toIndexedMap(question.getSuggestions());
    }

    /**
     * 从题库记录解析题目映射，优先使用 JSON 字段，其次使用列表字段。
     */
    private Map<String, String> resolveQuestionsMapFromQuestion(InterviewQuestion question) {
        if (question == null) {
            return Collections.emptyMap();
        }
        Map<String, String> fromJson = parseIndexedMap(question.getQuestionsJson());
        if (!fromJson.isEmpty()) {
            return fromJson;
        }
        return toIndexedMap(question.getQuestions());
    }

    /**
     * 解析 “序号 -> 文本” 的 JSON 映射，过滤空 key 与空值，解析失败返回空 Map。
     */
    private Map<String, String> parseIndexedMap(String json) {
        if (StrUtil.isBlank(json)) {
            return Collections.emptyMap();
        }
        try {
            Map<String, Object> parsed = JSON.parseObject(json, new TypeReference<LinkedHashMap<String, Object>>() {
            });
            if (parsed == null || parsed.isEmpty()) {
                return Collections.emptyMap();
            }
            Map<String, String> result = new LinkedHashMap<>();
            parsed.forEach((key, value) -> {
                if (StrUtil.isBlank(key) || value == null) {
                    return;
                }
                String text = String.valueOf(value).trim();
                if (StrUtil.isNotBlank(text)) {
                    result.put(key.trim(), text);
                }
            });
            return result;
        } catch (Exception ex) {
            log.warn("Failed to parse indexed map json", ex);
            return Collections.emptyMap();
        }
    }

    /**
     * 把字符串列表转换为 “1 起始序号 -> 文本” 的映射，跳过空值。
     */
    private Map<String, String> toIndexedMap(List<String> values) {
        if (values == null || values.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, String> result = new LinkedHashMap<>();
        for (int i = 0; i < values.size(); i++) {
            String value = values.get(i);
            if (StrUtil.isBlank(value)) {
                continue;
            }
            result.put(String.valueOf(i + 1), value.trim());
        }
        return result;
    }

    /**
     * 按序号升序拼接建议文本，非数字 key 退化为字典序比较。
     */
    private String sortAndJoinValues(Map<String, String> suggestionsMap) {
        if (suggestionsMap == null || suggestionsMap.isEmpty()) {
            return null;
        }
        return suggestionsMap.entrySet().stream()
                .sorted((e1, e2) -> {
                    try {
                        return Integer.compare(Integer.parseInt(e1.getKey()), Integer.parseInt(e2.getKey()));
                    } catch (NumberFormatException ex) {
                        return e1.getKey().compareTo(e2.getKey());
                    }
                })
                .map(Map.Entry::getValue)
                .collect(Collectors.joining("; "));
    }

    /**
     * 解析面试状态：会话状态为空时按是否有分数推断，未知状态原样返回。
     */
    private String resolveInterviewStatus(String sessionStatus, Integer totalScore) {
        if (StrUtil.isBlank(sessionStatus)) {
            return totalScore != null && totalScore > 0
                    ? InterviewSessionStatus.FINISHED.name()
                    : InterviewSessionStatus.DRAFT.name();
        }
        try {
            InterviewSessionStatus status = InterviewSessionStatus.valueOf(sessionStatus);
            if (status == InterviewSessionStatus.FINISHED && totalScore != null && totalScore > 0) {
                return InterviewSessionStatus.FINISHED.name();
            }
            return status.name();
        } catch (IllegalArgumentException ex) {
            log.warn("Unknown interview session status: {}", sessionStatus);
            return sessionStatus;
        }
    }

    /**
     * 计算面试时长秒数，起止时间缺失返回 null，非正时长返回 0。
     */
    private Integer calculateDurationSeconds(Date startTime, Date endTime) {
        if (startTime == null || endTime == null) {
            return null;
        }
        long durationMs = endTime.getTime() - startTime.getTime();
        if (durationMs <= 0) {
            return 0;
        }
        return (int) (durationMs / 1000);
    }

    /**
     * 构建会话快照 JSON，汇总会话主信息、轮次、雷达图与复盘反馈，供报告页恢复使用。
     */
    private String buildSessionSnapshotJson(
            InterviewSession session,
            Integer resumeScore,
            Integer interviewScore,
            Integer questionCount,
            String interviewDirection,
            String interviewStatus,
            String interviewSuggestions) {
        List<InterviewTurnLog> turns = runtimeSnapshotService.loadPersistedTurns(session.getSessionId());
        RadarChartDTO radarChart = interviewQuestionCacheService.getRadarChartData(session.getSessionId());

        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("sessionId", session.getSessionId());
        snapshot.put("sessionStatus", session.getStatus());
        snapshot.put("resumeFileUrl", session.getResumeFileUrl());
        snapshot.put("interviewerAgentId", session.getInterviewerAgentId());
        snapshot.put("sessionStartTime", session.getStartTime());
        snapshot.put("sessionEndTime", session.getEndTime());
        snapshot.put("resumeScore", resumeScore);
        snapshot.put("interviewScore", interviewScore);
        snapshot.put("questionCount", questionCount);
        snapshot.put("interviewDirection", interviewDirection);
        snapshot.put("interviewType", session.getInterviewType());
        snapshot.put("interviewStatus", interviewStatus);
        snapshot.put("interviewSuggestions", interviewSuggestions);
        snapshot.put("flow", interviewQuestionCacheService.getInterviewFlow(session.getSessionId()));
        snapshot.put("turns", turns);
        snapshot.put("radar", radarChart);
        snapshot.put("reviewFeedback", buildReviewFeedback(turns, radarChart, interviewSuggestions));
        snapshot.put("snapshotAt", new Date());
        return JSON.toJSONString(snapshot);
    }

    /**
     * Populate structured report fields for frontend rendering:
     * 1) radarChart / radarDimensions
     * 2) playbackItems
     * 补齐报告展示字段：雷达图与维度、答题回放列表、复盘反馈。
     */
    private void enrichReportFields(String sessionId, InterviewRecordDO record, InterviewRecordRespDTO respDTO) {
        Map<String, Object> snapshot = parseSnapshot(record == null ? null : record.getSessionSnapshotJson());

        RadarChartDTO radarChart = parseRadarFromSnapshot(snapshot);
        if (radarChart == null) {
            radarChart = interviewQuestionCacheService.getRadarChartData(sessionId);
        }
        if (radarChart == null) {
            radarChart = new RadarChartDTO();
        }
        respDTO.setRadarChart(radarChart);
        respDTO.setRadarDimensions(buildRadarDimensions(radarChart));

        List<InterviewTurnLog> turns = parseTurnsFromSnapshot(snapshot);
        if (turns == null || turns.isEmpty()) {
            turns = runtimeSnapshotService.loadPersistedTurns(sessionId);
        }
        respDTO.setPlaybackItems(buildPlaybackItems(turns));
        respDTO.setReviewFeedback(resolveReviewFeedback(snapshot, turns, radarChart, record));
    }

    /**
     * 解析记录中的会话快照 JSON，解析失败返回空 Map。
     */
    private Map<String, Object> parseSnapshot(String snapshotJson) {
        if (StrUtil.isBlank(snapshotJson)) {
            return Collections.emptyMap();
        }
        try {
            Map<String, Object> parsed = JSON.parseObject(
                    snapshotJson,
                    new TypeReference<LinkedHashMap<String, Object>>() {
                    }
            );
            return parsed == null ? Collections.emptyMap() : parsed;
        } catch (Exception ex) {
            log.warn("Failed to parse session snapshot json", ex);
            return Collections.emptyMap();
        }
    }

    /**
     * 从快照中解析雷达图数据，缺失或解析失败返回 null。
     */
    private RadarChartDTO parseRadarFromSnapshot(Map<String, Object> snapshot) {
        if (snapshot == null || snapshot.isEmpty()) {
            return null;
        }
        Object radarObj = snapshot.get("radar");
        if (radarObj == null) {
            return null;
        }
        try {
            return JSON.parseObject(JSON.toJSONString(radarObj), RadarChartDTO.class);
        } catch (Exception ex) {
            log.warn("Failed to parse radar from snapshot", ex);
            return null;
        }
    }

    /**
     * 从快照中解析轮次日志列表，缺失或解析失败返回空列表。
     */
    private List<InterviewTurnLog> parseTurnsFromSnapshot(Map<String, Object> snapshot) {
        if (snapshot == null || snapshot.isEmpty()) {
            return Collections.emptyList();
        }
        Object turnsObj = snapshot.get("turns");
        if (turnsObj == null) {
            return Collections.emptyList();
        }
        try {
            List<InterviewTurnLog> parsedTurns = JSON.parseArray(
                    JSON.toJSONString(turnsObj),
                    InterviewTurnLog.class
            );
            return parsedTurns == null ? Collections.emptyList() : parsedTurns;
        } catch (Exception ex) {
            log.warn("Failed to parse turns from snapshot", ex);
            return Collections.emptyList();
        }
    }

    /**
     * 解析复盘反馈：优先按当前数据重建，无内容时回退到快照中保存的反馈。
     */
    private InterviewReviewFeedbackRespDTO resolveReviewFeedback(
            Map<String, Object> snapshot,
            List<InterviewTurnLog> turns,
            RadarChartDTO radarChart,
            InterviewRecordDO record) {
        String interviewSuggestions = record == null ? null : record.getInterviewSuggestions();
        InterviewReviewFeedbackRespDTO rebuilt = buildReviewFeedback(turns, radarChart, interviewSuggestions);
        if (hasReviewFeedbackContent(rebuilt)) {
            return rebuilt;
        }
        InterviewReviewFeedbackRespDTO parsed = parseReviewFeedbackFromSnapshot(snapshot);
        return parsed != null ? parsed : rebuilt;
    }

    /**
     * 从快照中解析复盘反馈对象，缺失或解析失败返回 null。
     */
    private InterviewReviewFeedbackRespDTO parseReviewFeedbackFromSnapshot(Map<String, Object> snapshot) {
        if (snapshot == null || snapshot.isEmpty()) {
            return null;
        }
        Object reviewFeedback = snapshot.get("reviewFeedback");
        if (reviewFeedback == null) {
            return null;
        }
        try {
            return JSON.parseObject(JSON.toJSONString(reviewFeedback), InterviewReviewFeedbackRespDTO.class);
        } catch (Exception ex) {
            log.warn("Failed to parse review feedback from snapshot", ex);
            return null;
        }
    }

    /**
     * 基于轮次与雷达图重建复盘反馈：总体评价、亮点、改进建议与下一步行动。
     */
    private InterviewReviewFeedbackRespDTO buildReviewFeedback(
            List<InterviewTurnLog> turns,
            RadarChartDTO radarChart,
            String interviewSuggestions) {
        InterviewReviewFeedbackRespDTO reviewFeedback = new InterviewReviewFeedbackRespDTO();
        reviewFeedback.setOverallComment(buildOverallComment(radarChart));
        List<String> highlights = buildHighlights(turns, radarChart);
        List<String> improvementTips = buildImprovementTips(turns, radarChart);
        reviewFeedback.setHighlights(highlights);
        reviewFeedback.setImprovementTips(improvementTips);
        reviewFeedback.setNextActions(buildNextActions(interviewSuggestions, improvementTips));
        return reviewFeedback;
    }

    /**
     * 判断复盘反馈是否包含任意有效内容。
     */
    private boolean hasReviewFeedbackContent(InterviewReviewFeedbackRespDTO reviewFeedback) {
        if (reviewFeedback == null) {
            return false;
        }
        return StrUtil.isNotBlank(reviewFeedback.getOverallComment())
                || (reviewFeedback.getHighlights() != null && !reviewFeedback.getHighlights().isEmpty())
                || (reviewFeedback.getImprovementTips() != null && !reviewFeedback.getImprovementTips().isEmpty())
                || (reviewFeedback.getNextActions() != null && !reviewFeedback.getNextActions().isEmpty());
    }

    /**
     * 按综合分档生成总体评价，并补充最强与最弱维度的建议。
     */
    private String buildOverallComment(RadarChartDTO radarChart) {
        int finalScore = radarChart == null ? 0 : clampScore(radarChart.getPotentialIndex());
        String baseComment;
        if (finalScore >= 85) {
            baseComment = "Overall performance is strong and already competitive for interviews.";
        } else if (finalScore >= 70) {
            baseComment = "Overall performance is solid, with clear strengths and room for further polishing.";
        } else if (finalScore >= 60) {
            baseComment = "Overall performance meets the baseline, but answer structure and role-fit still need improvement.";
        } else {
            baseComment = "Overall performance is below expectation; prioritize role-fit, depth of answers, and communication stability.";
        }

        String strongest = resolveDimensionLabel(findStrongestDimensionKey(radarChart));
        String weakest = resolveDimensionLabel(findWeakestDimensionKey(radarChart));
        if (StrUtil.isBlank(strongest) || StrUtil.isBlank(weakest) || strongest.equals(weakest)) {
            return baseComment;
        }
        return baseComment + " Current strength is " + strongest + ", and prioritize improving " + weakest + ".";
    }

    /**
     * 生成亮点列表：优先取高分轮次反馈，不足时按高分维度补充。
     */
    private List<String> buildHighlights(List<InterviewTurnLog> turns, RadarChartDTO radarChart) {
        List<String> highlights = extractFeedbackLines(turns, 80, Integer.MAX_VALUE, 3);
        if (!highlights.isEmpty()) {
            return highlights;
        }

        LinkedHashSet<String> generated = new LinkedHashSet<>();
        if (radarChart != null && clampScore(radarChart.getResumeScore()) >= 75) {
            generated.add("Your resume is well aligned to the target role, with clear highlights in core experience.");
        }
        if (radarChart != null && clampScore(radarChart.getInterviewPerformance()) >= 75) {
            generated.add("Your answer structure is complete and remains focused on the question.");
        }
        if (radarChart != null && clampScore(radarChart.getDemeanorEvaluation()) >= 75) {
            generated.add("Your demeanor and expression are stable, and your on-site delivery feels natural.");
        }
        if (radarChart != null && clampScore(radarChart.getProfessionalSkills()) >= 75) {
            generated.add("Your professional skills are communicated clearly with concrete technical understanding.");
        }
        return limitList(generated, 3);
    }

    /**
     * 生成改进建议：优先取低分轮次反馈，不足时按低分维度补充。
     */
    private List<String> buildImprovementTips(List<InterviewTurnLog> turns, RadarChartDTO radarChart) {
        List<String> improvements = extractFeedbackLines(turns, 0, 69, 3);
        if (!improvements.isEmpty()) {
            return improvements;
        }

        LinkedHashSet<String> generated = new LinkedHashSet<>();
        appendImprovementByDimension(generated, "resume_score", radarChart);
        appendImprovementByDimension(generated, "interview_performance", radarChart);
        appendImprovementByDimension(generated, "demeanor_evaluation", radarChart);
        appendImprovementByDimension(generated, "professional_skills", radarChart);
        return limitList(generated, 3);
    }

    /**
     * 按维度补充分数低于阈值的改进建议，最多保留 3 条。
     */
    private void appendImprovementByDimension(LinkedHashSet<String> generated, String dimensionKey, RadarChartDTO radarChart) {
        if (generated.size() >= 3 || radarChart == null) {
            return;
        }
        int score = getDimensionScore(radarChart, dimensionKey);
        if (score >= 75) {
            return;
        }
        switch (dimensionKey) {
            case "resume_score" ->
                    generated.add("Refine resume highlights for the target role and add concrete project outcomes and business impact.");
            case "interview_performance" ->
                    generated.add("Answer more directly, and use a clear structure with case, action, and result.");
            case "demeanor_evaluation" ->
                    generated.add("Practice pace control, pauses, and steadier expression in front of the camera.");
            case "professional_skills" ->
                    generated.add("Explain technical decisions in more detail, including trade-offs, challenges, and outcomes.");
            default -> {
            }
        }
    }

    /**
     * 生成下一步行动：合并面试建议与改进建议，去重后限制条数。
     */
    private List<String> buildNextActions(String interviewSuggestions, List<String> improvementTips) {
        LinkedHashSet<String> actions = new LinkedHashSet<>();
        if (StrUtil.isNotBlank(interviewSuggestions)) {
            actions.addAll(parseInterviewSuggestions(interviewSuggestions).values());
        }
        if (improvementTips != null) {
            actions.addAll(improvementTips);
        }
        return limitList(actions, 3);
    }

    /**
     * 从轮次反馈中抽取指定分数区间的句子，去重后限制条数。
     */
    private List<String> extractFeedbackLines(List<InterviewTurnLog> turns, int minScore, int maxScore, int limit) {
        if (turns == null || turns.isEmpty()) {
            return Collections.emptyList();
        }
        LinkedHashSet<String> lines = new LinkedHashSet<>();
        for (InterviewTurnLog turn : turns) {
            if (turn == null || turn.getScore() == null || StrUtil.isBlank(turn.getFeedback())) {
                continue;
            }
            int score = clampScore(turn.getScore());
            if (score < minScore || score > maxScore) {
                continue;
            }
            for (String sentence : splitFeedback(turn.getFeedback())) {
                if (lines.size() >= limit) {
                    break;
                }
                lines.add(sentence);
            }
            if (lines.size() >= limit) {
                break;
            }
        }
        return new ArrayList<>(lines);
    }

    /**
     * 按中英文标点切分反馈文本，过滤过短句子并统一补句号。
     */
    private List<String> splitFeedback(String feedback) {
        if (StrUtil.isBlank(feedback)) {
            return Collections.emptyList();
        }
        String[] rawParts = feedback.split("[;.!?\\r\\n\\u3002\\uff1b\\uff01\\uff1f]+");
        List<String> parts = new ArrayList<>(rawParts.length);
        for (String rawPart : rawParts) {
            String sentence = StrUtil.trim(rawPart);
            if (StrUtil.isBlank(sentence) || sentence.length() < 6) {
                continue;
            }
            parts.add(sentence.endsWith(".") ? sentence : sentence + ".");
        }
        return parts;
    }

    /**
     * 截取集合前 limit 条非空文本，超过上限即停止。
     */
    private List<String> limitList(LinkedHashSet<String> items, int limit) {
        if (items == null || items.isEmpty() || limit <= 0) {
            return Collections.emptyList();
        }
        List<String> result = new ArrayList<>(limit);
        for (String item : items) {
            if (StrUtil.isBlank(item)) {
                continue;
            }
            result.add(item);
            if (result.size() >= limit) {
                break;
            }
        }
        return result;
    }

    /**
     * 找出分数最高的雷达维度 key。
     */
    private String findStrongestDimensionKey(RadarChartDTO radarChart) {
        return findDimensionKey(radarChart, true);
    }

    /**
     * 找出分数最低的雷达维度 key。
     */
    private String findWeakestDimensionKey(RadarChartDTO radarChart) {
        return findDimensionKey(radarChart, false);
    }

    /**
     * 在固定维度中查找最高或最低分的维度 key。
     */
    private String findDimensionKey(RadarChartDTO radarChart, boolean strongest) {
        if (radarChart == null) {
            return null;
        }
        String[] keys = new String[]{"resume_score", "interview_performance", "demeanor_evaluation", "professional_skills"};
        String targetKey = null;
        int targetScore = strongest ? Integer.MIN_VALUE : Integer.MAX_VALUE;
        for (String key : keys) {
            int score = getDimensionScore(radarChart, key);
            if ((strongest && score > targetScore) || (!strongest && score < targetScore)) {
                targetScore = score;
                targetKey = key;
            }
        }
        return targetKey;
    }

    /**
     * 按维度 key 读取分数并限制在 0-100，未知 key 返回 0。
     */
    private int getDimensionScore(RadarChartDTO radarChart, String key) {
        if (radarChart == null || StrUtil.isBlank(key)) {
            return 0;
        }
        return switch (key) {
            case "resume_score" -> clampScore(radarChart.getResumeScore());
            case "interview_performance" -> clampScore(radarChart.getInterviewPerformance());
            case "demeanor_evaluation" -> clampScore(radarChart.getDemeanorEvaluation());
            case "professional_skills" -> clampScore(radarChart.getProfessionalSkills());
            case "potential_index" -> clampScore(radarChart.getPotentialIndex());
            default -> 0;
        };
    }

    /**
     * 把维度 key 映射为中文名称，未知 key 返回 null。
     */
    private String resolveDimensionLabel(String dimensionKey) {
        if (StrUtil.isBlank(dimensionKey)) {
            return null;
        }
        return switch (dimensionKey) {
            case "resume_score" -> "简历匹配度";
            case "interview_performance" -> "答题表现";
            case "demeanor_evaluation" -> "神态表达";
            case "professional_skills" -> "专业能力呈现";
            case "potential_index" -> "综合潜力";
            default -> null;
        };
    }

    /**
     * 把雷达图数据转换为前端可直接渲染的维度列表。
     */
    private List<RadarDimensionItemRespDTO> buildRadarDimensions(RadarChartDTO radarChart) {
        if (radarChart == null) {
            return Collections.emptyList();
        }
        List<RadarDimensionItemRespDTO> dimensions = new ArrayList<>(5);
        dimensions.add(new RadarDimensionItemRespDTO("resume_score", "Resume", clampScore(radarChart.getResumeScore())));
        dimensions.add(new RadarDimensionItemRespDTO("interview_performance", "Interview", clampScore(radarChart.getInterviewPerformance())));
        dimensions.add(new RadarDimensionItemRespDTO("demeanor_evaluation", "Demeanor", clampScore(radarChart.getDemeanorEvaluation())));
        dimensions.add(new RadarDimensionItemRespDTO("professional_skills", "Skills", clampScore(radarChart.getProfessionalSkills())));
        dimensions.add(new RadarDimensionItemRespDTO("potential_index", "Potential", clampScore(radarChart.getPotentialIndex())));
        return dimensions;
    }

    /**
     * 把轮次日志转换为答题回放列表，按顺序编号并跳过空轮次。
     */
    private List<InterviewPlaybackItemRespDTO> buildPlaybackItems(List<InterviewTurnLog> turns) {
        if (turns == null || turns.isEmpty()) {
            return Collections.emptyList();
        }
        List<InterviewPlaybackItemRespDTO> items = new ArrayList<>(turns.size());
        for (int i = 0; i < turns.size(); i++) {
            InterviewTurnLog turn = turns.get(i);
            if (turn == null) {
                continue;
            }
            InterviewPlaybackItemRespDTO item = new InterviewPlaybackItemRespDTO();
            item.setSeq(i + 1);
            item.setTimestamp(turn.getTimestamp());
            item.setRequestId(turn.getRequestId());
            item.setQuestionNumber(turn.getQuestionNumber());
            item.setQuestionContent(turn.getQuestionContent());
            item.setAnswerContent(turn.getAnswerContent());
            item.setScore(turn.getScore());
            item.setFeedback(turn.getFeedback());
            item.setTotalScore(turn.getTotalScore());
            item.setFollowUpNeeded(turn.getFollowUpNeeded());
            item.setIsFollowUp(turn.getIsFollowUp());
            item.setFollowUpCount(turn.getFollowUpCount());
            item.setNextQuestionNumber(turn.getNextQuestionNumber());
            item.setNextQuestion(turn.getNextQuestion());
            item.setFinished(turn.getFinished());
            items.add(item);
        }
        return items;
    }

    /**
     * 把分数限制在 0-100，null 或负数按 0 处理。
     */
    private Integer clampScore(Integer score) {
        if (score == null) {
            return 0;
        }
        if (score < 0) {
            return 0;
        }
        return Math.min(score, 100);
    }

    /**
     * 解析面试建议字符串，按分号切分并生成从 1 开始的序号映射。
     */
    @Override
    public Map<String, String> parseInterviewSuggestions(String suggestionsString) {
        // （1）按分号切分，过滤空项后生成序号映射
        Map<String, String> suggestionsMap = new LinkedHashMap<>();
        if (StrUtil.isBlank(suggestionsString)) {
            return suggestionsMap;
        }

        String[] suggestions = suggestionsString.split(";");
        for (int i = 0; i < suggestions.length; i++) {
            String suggestion = suggestions[i].trim();
            if (StrUtil.isNotBlank(suggestion)) {
                suggestionsMap.put(String.valueOf(i + 1), suggestion);
            }
        }

        log.debug("Parsed interview suggestions, rawLength={}, parsedCount={}",
                suggestionsString.length(), suggestionsMap.size());
        return suggestionsMap;
    }
}
