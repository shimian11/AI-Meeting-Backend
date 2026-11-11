package com.banma.interview.interview.application.runtime;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.banma.interview.interview.api.io.req.DemeanorScoreDTO;
import com.banma.interview.interview.dao.entity.InterviewQuestion;
import com.banma.interview.interview.dao.entity.InterviewRecordDO;
import com.banma.interview.interview.dao.entity.InterviewSession;
import com.banma.interview.interview.dao.entity.InterviewSessionRuntimeSnapshot;
import com.banma.interview.interview.dao.mapper.InterviewRecordMapper;
import com.banma.interview.interview.service.InterviewQuestionCacheService;
import com.banma.interview.interview.service.InterviewQuestionService;
import com.banma.interview.interview.service.InterviewSessionService;
import com.banma.interview.interview.service.cache.InterviewCacheKeys;
import com.banma.interview.interview.service.model.InterviewFlowState;
import com.banma.interview.interview.service.model.InterviewRuntimeConfidence;
import com.banma.interview.interview.service.model.InterviewRuntimeLoadMode;
import com.banma.interview.interview.service.model.InterviewRuntimeScoreAggregate;
import com.banma.interview.interview.service.model.InterviewSessionStatus;
import com.banma.interview.interview.service.model.InterviewTurnLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 提供面试会话运行态的懒恢复与缓存重建能力，
 * 用于在 Redis 运行态缺失时按需从快照、报告和主数据中恢复题目流转与得分状态。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class InterviewSessionRuntimeRehydrateService {

    /** 运行态缓存过期时长（小时） */
    private static final long CACHE_EXPIRE_HOURS = 24L;
    /** 从节点等待 leader 结果的最大复查次数 */
    private static final int FOLLOWER_RECHECK_TIMES = 4;
    /** 每次复查的间隔（毫秒） */
    private static final long FOLLOWER_RECHECK_MILLIS = 80L;

    /** 运行态快照读写与刷新触发 */
    private final InterviewSessionRuntimeSnapshotService runtimeSnapshotService;
    /** 运行态分布式锁 */
    private final InterviewSessionRuntimeLockService runtimeLockService;
    /** 面试会话持久化服务（Mongo） */
    private final InterviewSessionService interviewSessionService;
    /** 面试题目持久化服务（MySQL） */
    private final InterviewQuestionService interviewQuestionService;
    /** 题目、流程与得分的 Redis 缓存读写 */
    private final InterviewQuestionCacheService interviewQuestionCacheService;
    /** 面试记录 Mapper（MyBatis） */
    private final InterviewRecordMapper interviewRecordMapper;
    /** Redis 模板，用于缓存读写 */
    private final StringRedisTemplate stringRedisTemplate;

    /** 按完整运行态范围确保会话运行态可用。 */
    public InterviewSessionRuntimeView ensureRuntime(String sessionId, InterviewRuntimeLoadMode loadMode) {
        return ensureRuntime(sessionId, loadMode, InterviewRuntimeRehydrateScope.FULL_RUNTIME);
    }

    /**
     * 确保会话运行态可用：缓存已就绪直接复用，否则加分布式锁按快照或主数据重建缓存。
     *
     * @param sessionId 面试会话 id
     * @param loadMode 本次读取的加载模式，用于标记视图用途
     * @param scope 需要恢复的数据范围，为空时按完整运行态处理
     * @return 运行态视图，无法恢复时返回只读兜底视图
     */
    public InterviewSessionRuntimeView ensureRuntime(
            String sessionId,
            InterviewRuntimeLoadMode loadMode,
            InterviewRuntimeRehydrateScope scope) {
        // （1）归一化恢复范围，sessionId 为空时直接返回只读视图
        InterviewRuntimeRehydrateScope resolvedScope = scope == null ? InterviewRuntimeRehydrateScope.FULL_RUNTIME : scope;
        if (StrUtil.isBlank(sessionId)) {
            return buildView(loadMode, InterviewRuntimeRestoreSource.NONE, InterviewRuntimeConfidence.READ_ONLY, false, null);
        }
        // （2）运行态已就绪时直接返回缓存视图
        if (isRuntimeReady(sessionId, resolvedScope)) {
            return buildView(loadMode, InterviewRuntimeRestoreSource.CACHE, InterviewRuntimeConfidence.EXACT, false, getRuntimeSnapshot(sessionId));
        }
        RLock lock = null;
        try {
            // （3）先尝试加锁；未抢到时等待其他节点恢复完成，再短暂重试一次
            lock = runtimeLockService.acquire(sessionId);
            if (lock == null) {
                InterviewSessionRuntimeView reused = waitForRecoveredRuntime(sessionId, loadMode, resolvedScope);
                if (reused != null) {
                    return reused;
                }
                lock = runtimeLockService.acquire(sessionId, FOLLOWER_RECHECK_MILLIS);
            }
            // （4）其他节点可能已恢复，二次确认运行态是否就绪
            if (isRuntimeReady(sessionId, resolvedScope)) {
                return buildView(loadMode, InterviewRuntimeRestoreSource.CACHE, InterviewRuntimeConfidence.EXACT, false, getRuntimeSnapshot(sessionId));
            }
            // （5）仍未拿到锁时放弃重建，返回基于快照的只读视图
            if (lock == null) {
                log.warn(
                "Failed to acquire runtime rehydrate lock after waiting, sessionId={}",
                sessionId);

                return buildView(
                loadMode, InterviewRuntimeRestoreSource.NONE, InterviewRuntimeConfidence.READ_ONLY, false, getRuntimeSnapshot(sessionId));
            }
            // （6）持锁执行缓存重建
            InterviewSessionRuntimeView rebuilt = rebuildRuntime(sessionId, loadMode, resolvedScope);
            if (rebuilt != null) {
                return rebuilt;
            }
        } catch (InterruptedException ex) {
            // （7）线程被中断时恢复中断标记
            Thread.currentThread().interrupt();
        } catch (Exception ex) {
            log.warn("Failed to ensure runtime, sessionId={}", sessionId, ex);
        } finally {
            // （8）无论成功失败都释放锁
            runtimeLockService.release(lock);
        }
        // （9）兜底返回只读视图
        return buildView(loadMode, InterviewRuntimeRestoreSource.NONE, InterviewRuntimeConfidence.READ_ONLY, false, getRuntimeSnapshot(sessionId));
    }

    /**
     * 查询会话最新一条未删除的面试记录，用于兜底恢复。
     */
    public InterviewRecordDO getLatestRecord(String sessionId) {
        // （1）会话 id 为空时返回 null
        if (StrUtil.isBlank(sessionId)) {
            return null;
        }
        // （2）按更新时间倒序取第一条未删除记录
        LambdaQueryWrapper<InterviewRecordDO> queryWrapper = Wrappers.lambdaQuery(InterviewRecordDO.class)
                .eq(InterviewRecordDO::getSessionId, sessionId)
                .eq(InterviewRecordDO::getDelFlag, 0)
                .orderByDesc(InterviewRecordDO::getUpdateTime)
                .last("limit 1");
        return interviewRecordMapper.selectOne(queryWrapper);
    }

    /** 查询会话运行态快照，不存在时返回 null。 */
    public InterviewSessionRuntimeSnapshot getRuntimeSnapshot(String sessionId) {
        return runtimeSnapshotService.findSnapshot(sessionId).orElse(null);
    }

    /**
     * 组装运行态视图，按需附带冷热快照明细。
     */
    private InterviewSessionRuntimeView buildView(
            InterviewRuntimeLoadMode loadMode,
            InterviewRuntimeRestoreSource restoreSource,
            InterviewRuntimeConfidence confidence,
            boolean cacheRebuilt,
            InterviewSessionRuntimeSnapshot snapshot) {
        // （1）填充视图基础信息，并在有快照时附带冷热快照明细
        return InterviewSessionRuntimeView.builder()
                .loadMode(loadMode)
                .restoreSource(restoreSource)
                .confidence(confidence)
                .cacheRebuilt(cacheRebuilt)
                .hotSnapshot(snapshot == null ? null : runtimeSnapshotService.findHotSnapshot(snapshot.getSessionId()).orElse(null))
                .coldSnapshot(snapshot == null ? null : runtimeSnapshotService.findColdSnapshot(snapshot.getSessionId()).orElse(null))
                .snapshot(snapshot)
                .build();
    }

    /**
     * 重建运行态缓存：快照完整时直接回写缓存，否则聚合主数据后写部分缓存。
     */
    private InterviewSessionRuntimeView rebuildRuntime(
            String sessionId,
            InterviewRuntimeLoadMode loadMode,
            InterviewRuntimeRehydrateScope scope) {
        // （1）快照满足当前恢复范围时优先用快照回写缓存
        InterviewSessionRuntimeSnapshot snapshot = getRuntimeSnapshot(sessionId);
        if (snapshot != null && canRehydrateFromSnapshot(snapshot, scope)) {
            writeSnapshotToCache(sessionId, snapshot, scope);
            return buildView(
                    loadMode,
                    InterviewRuntimeRestoreSource.RUNTIME_SNAPSHOT,
                    snapshot.getRebuildConfidence() == null ? InterviewRuntimeConfidence.EXACT : snapshot.getRebuildConfidence(),
                    true,
                    snapshot
            );
        }

        // （2）快照不可用时聚合成交记录、会话、题目等主数据
        InterviewRecordDO record = getLatestRecord(sessionId);
        InterviewSession session = interviewSessionService.getBySessionId(sessionId);
        InterviewQuestion question = interviewQuestionService.getBySessionId(sessionId);
        RuntimeMaterial material = buildRuntimeMaterial(sessionId, session, question, record, snapshot);
        if (material == null) {
            return buildView(loadMode, InterviewRuntimeRestoreSource.NONE, InterviewRuntimeConfidence.READ_ONLY, false, snapshot);
        }
        // （3）写操作场景下缺少题目材料时，只写部分缓存并降低置信度
        if (requiresQuestionMaterial(scope) && !hasQuestionMaterial(material.questions)
                && loadMode == InterviewRuntimeLoadMode.READ_WRITE_REQUIRED) {
            InterviewRuntimeConfidence confidence = isTerminalSession(session)
                    ? InterviewRuntimeConfidence.TERMINAL
                    : InterviewRuntimeConfidence.READ_ONLY;
            writePartialMaterial(sessionId, material, confidence == InterviewRuntimeConfidence.TERMINAL, scope);
            return buildView(loadMode, resolveRestoreSource(record, question), confidence, true, snapshot);
        }
        // （4）正常回写部分缓存并返回视图
        writePartialMaterial(sessionId, material, isTerminalSession(session), scope);
        return buildView(
                loadMode,
                resolveRestoreSource(record, question),
                material.confidence,
                true,
                snapshot
        );
    }

    /**
     * 从快照、会话、题目与记录中聚合出恢复所需的运行态材料。
     *
     * @return 聚合结果，四类数据均缺失时返回 null
     */
    private RuntimeMaterial buildRuntimeMaterial(
            String sessionId,
            InterviewSession session,
            InterviewQuestion question,
            InterviewRecordDO record,
            InterviewSessionRuntimeSnapshot snapshot) {
        // （1）所有来源均缺失时无法构造材料
        if (session == null && question == null && record == null && snapshot == null) {
            return null;
        }
        // （2）逐项解析题目、建议、简历上下文与分数
        RuntimeMaterial material = new RuntimeMaterial();
        material.questions = parseStringMap(question == null ? null : question.getQuestionsJson(), question == null ? null : question.getQuestions());
        material.suggestions = parseSuggestions(question, record);
        material.resumeContext = parseResumeContext(question);
        material.resumeScore = resolveResumeScore(question, snapshot, record);
        material.demeanorScore = resolveDemeanorScore(snapshot, record);
        material.demeanorDetails = snapshot == null ? null : snapshot.getDemeanorDetails();
        material.direction = resolveDirection(session, question, snapshot, record);
        material.turns = resolveTurns(sessionId, snapshot, record);
        material.followUpQuestions = deriveFollowUpQuestions(snapshot, material.turns);
        material.scoreAggregate = resolveScoreAggregate(snapshot, material.turns, record);
        // （3）解析流程状态并评估本次恢复的置信度
        material.flow = resolveFlow(session, material.questions, material.turns, snapshot);
        material.confidence = resolveConfidence(session, material.questions, material.flow);
        return material;
    }

    /**
     * 作为 follower 等待其他节点恢复完成，期间按固定间隔复查运行态是否就绪。
     *
     * @return 就绪后的缓存视图，重试结束仍未就绪时返回 null
     */
    private InterviewSessionRuntimeView waitForRecoveredRuntime(
            String sessionId,
            InterviewRuntimeLoadMode loadMode,
            InterviewRuntimeRehydrateScope scope) {
        // （1）按固定次数与间隔轮询运行态是否就绪
        for (int attempt = 0; attempt < FOLLOWER_RECHECK_TIMES; attempt++) {
            if (isRuntimeReady(sessionId, scope)) {
                return buildView(loadMode, InterviewRuntimeRestoreSource.CACHE, InterviewRuntimeConfidence.EXACT, false, getRuntimeSnapshot(sessionId));
            }
            try {
                TimeUnit.MILLISECONDS.sleep(FOLLOWER_RECHECK_MILLIS);
            } catch (InterruptedException ex) {
                // （2）等待被中断时恢复中断标记并放弃等待
                Thread.currentThread().interrupt();
                return null;
            }
        }
        // （3）最后一次复查，仍未就绪返回 null
        return isRuntimeReady(sessionId, scope)
                ? buildView(loadMode, InterviewRuntimeRestoreSource.CACHE, InterviewRuntimeConfidence.EXACT, false, getRuntimeSnapshot(sessionId))
                : null;
    }

    /**
     * 判断快照是否覆盖当前恢复范围所需的数据，缺失关键材料时不可直接回写缓存。
     */
    private boolean canRehydrateFromSnapshot(
            InterviewSessionRuntimeSnapshot snapshot,
            InterviewRuntimeRehydrateScope scope) {
        // （1）快照为空时不可用
        if (snapshot == null) {
            return false;
        }
        // （2）逐项校验题目材料、流程、分数、轮次、建议与简历材料是否齐全
        if (scope.includesQuestionMaterial() && !hasQuestionMaterial(snapshot.getQuestions())) {
            return false;
        }
        if (scope.includesFlow() && snapshot.getFlow() == null) {
            return false;
        }
        if (scope.includesScore() && snapshot.getScoreAggregate() == null && (snapshot.getRecentTurns() == null || snapshot.getRecentTurns().isEmpty())) {
            return false;
        }
        if (scope.includesTurns() && (snapshot.getRecentTurns() == null || snapshot.getRecentTurns().isEmpty())
                && (snapshot.getArchiveWatermark() == null || snapshot.getArchiveWatermark() <= 0)) {
            return false;
        }
        if (scope.includesSuggestionMaterial() && (snapshot.getSuggestions() == null || snapshot.getSuggestions().isEmpty())) {
            return false;
        }
        if (scope.includesResumeMaterial()
                && snapshot.getResumeContext() == null
                && snapshot.getResumeScore() == null
                && StrUtil.isBlank(snapshot.getDirection())
                && snapshot.getDemeanorScore() == null
                && snapshot.getDemeanorDetails() == null) {
            return false;
        }
        return true;
    }

    private boolean requiresQuestionMaterial(InterviewRuntimeRehydrateScope scope) {
        return scope.includesQuestionMaterial() || scope.includesFlow();
    }

    /**
     * 按恢复范围检查对应 Redis 缓存键是否已有数据，用于判断运行态是否就绪。
     */
    private boolean isRuntimeReady(String sessionId, InterviewRuntimeRehydrateScope scope) {
        // （1）不同范围校验不同的缓存键组合
        return switch (scope) {
            case FLOW_ONLY -> hasHashEntries(InterviewCacheKeys.questions(sessionId))
                    && hasHashEntries(InterviewCacheKeys.flow(sessionId));
            case SCORE_ONLY -> hasValue(InterviewCacheKeys.sessionScore(sessionId));
            case PLAYBACK_ONLY -> hasListEntries(InterviewCacheKeys.turns(sessionId));
            case MATERIAL_ONLY -> hasHashEntries(InterviewCacheKeys.questions(sessionId))
                    && (hasHashEntries(InterviewCacheKeys.suggestions(sessionId))
                    || hasValue(InterviewCacheKeys.resumeScore(sessionId))
                    || hasValue(InterviewCacheKeys.direction(sessionId))
                    || hasJsonValue(InterviewCacheKeys.resumeContext(sessionId)));
            case HOT_RUNTIME -> hasHashEntries(InterviewCacheKeys.questions(sessionId))
                    && hasHashEntries(InterviewCacheKeys.flow(sessionId))
                    && (hasValue(InterviewCacheKeys.sessionScore(sessionId)) || hasListEntries(InterviewCacheKeys.turns(sessionId)));
            case FULL_RUNTIME -> hasHashEntries(InterviewCacheKeys.questions(sessionId))
                    && hasHashEntries(InterviewCacheKeys.flow(sessionId))
                    && (hasValue(InterviewCacheKeys.sessionScore(sessionId)) || hasListEntries(InterviewCacheKeys.turns(sessionId)))
                    && (hasHashEntries(InterviewCacheKeys.suggestions(sessionId))
                    || hasValue(InterviewCacheKeys.resumeScore(sessionId))
                    || hasValue(InterviewCacheKeys.direction(sessionId))
                    || hasJsonValue(InterviewCacheKeys.resumeContext(sessionId)));
        };
    }

    /**
     * 把运行态快照按指定范围回写到 Redis 缓存。
     */
    private void writeSnapshotToCache(
            String sessionId,
            InterviewSessionRuntimeSnapshot snapshot,
            InterviewRuntimeRehydrateScope scope) {
        // （1）快照为空时无需回写
        if (snapshot == null) {
            return;
        }
        // （2）需要轮次或分数时优先取已归档轮次，缺失再用快照中的最近轮次
        List<InterviewTurnLog> turns = Collections.emptyList();
        if (scope.includesTurns() || scope.includesScore()) {
            turns = runtimeSnapshotService.loadPersistedTurns(sessionId);
            if (turns == null || turns.isEmpty()) {
                turns = snapshot.getRecentTurns();
            }
        }
        // （3）按范围逐类回写题目、建议与简历/神态材料
        if (scope.includesQuestionMaterial()) {
            writeHash(InterviewCacheKeys.questions(sessionId), snapshot.getQuestions());
        }
        if (scope.includesSuggestionMaterial()) {
            writeHash(InterviewCacheKeys.suggestions(sessionId), snapshot.getSuggestions());
        }
        if (scope.includesResumeMaterial()) {
            writeJsonValue(InterviewCacheKeys.resumeContext(sessionId), snapshot.getResumeContext());
            writeValue(InterviewCacheKeys.resumeScore(sessionId), snapshot.getResumeScore());
            writeValue(InterviewCacheKeys.direction(sessionId), snapshot.getDirection());
            writeValue(InterviewCacheKeys.demeanorScore(sessionId), snapshot.getDemeanorScore());
            writeDemeanorDetails(sessionId, snapshot.getDemeanorDetails());
        }
        // （4）回写流程、追问、轮次、分数与请求 id 缓存
        if (scope.includesFlow()) {
            writeFlow(sessionId, snapshot.getFlow());
        }
        if (scope.includesFollowUpQuestions()) {
            writeHash(InterviewCacheKeys.followUpQuestions(sessionId), snapshot.getFollowUpQuestions());
        }
        if (scope.includesTurns()) {
            writeTurns(sessionId, turns);
        }
        if (scope.includesScore()) {
            writeScoreAggregate(sessionId, snapshot.getScoreAggregate(), turns);
        }
        if (scope.includesRequestIds()) {
            writeRequestIds(sessionId, turns);
        }
    }

    /**
     * 把聚合出的材料按指定范围写入 Redis 缓存，必要时直接写完成态流程。
     *
     * @param terminal 会话是否已结束（结束时流程直接写成完成态）
     */
    private void writePartialMaterial(
            String sessionId,
            RuntimeMaterial material,
            boolean terminal,
            InterviewRuntimeRehydrateScope scope) {
        // （1）按范围回写题目、建议与简历/神态材料
        if (scope.includesQuestionMaterial()) {
            writeHash(InterviewCacheKeys.questions(sessionId), material.questions);
        }
        if (scope.includesSuggestionMaterial()) {
            writeHash(InterviewCacheKeys.suggestions(sessionId), material.suggestions);
        }
        if (scope.includesResumeMaterial()) {
            writeJsonValue(InterviewCacheKeys.resumeContext(sessionId), material.resumeContext);
            writeValue(InterviewCacheKeys.resumeScore(sessionId), material.resumeScore);
            writeValue(InterviewCacheKeys.direction(sessionId), material.direction);
            writeValue(InterviewCacheKeys.demeanorScore(sessionId), material.demeanorScore);
            writeDemeanorDetails(sessionId, material.demeanorDetails);
        }
        // （2）回写追问、轮次、分数与请求 id 缓存
        if (scope.includesFollowUpQuestions()) {
            writeHash(InterviewCacheKeys.followUpQuestions(sessionId), material.followUpQuestions);
        }
        if (scope.includesTurns()) {
            writeTurns(sessionId, material.turns);
        }
        if (scope.includesScore()) {
            writeScoreAggregate(sessionId, material.scoreAggregate, material.turns);
        }
        if (scope.includesRequestIds()) {
            writeRequestIds(sessionId, material.turns);
        }
        // （3）回写流程：已结束的会话直接记为完成态，否则写入推导出的流程
        if (scope.includesFlow()) {
            if (terminal) {
                writeFlow(sessionId, buildCompletedFlow(material.questions == null ? 0 : material.questions.size()));
                return;
            }
            writeFlow(sessionId, material.flow);
        }
    }

    private boolean hasHashEntries(String key) {
        Long size = stringRedisTemplate.opsForHash().size(key);
        return size != null && size > 0;
    }

    private boolean hasListEntries(String key) {
        Long size = stringRedisTemplate.opsForList().size(key);
        return size != null && size > 0;
    }

    private boolean hasValue(String key) {
        return StrUtil.isNotBlank(stringRedisTemplate.opsForValue().get(key));
    }

    private boolean hasJsonValue(String key) {
        return StrUtil.isNotBlank(stringRedisTemplate.opsForValue().get(key));
    }

    /**
     * 全量覆盖写入一个 hash 缓存并设置统一过期时间。
     */
    private void writeHash(String key, Map<String, String> values) {
        // （1）先删除旧键，避免残留历史字段
        stringRedisTemplate.delete(key);
        if (values == null || values.isEmpty()) {
            return;
        }
        // （2）批量写入并设置过期时间
        stringRedisTemplate.opsForHash().putAll(key, values);
        stringRedisTemplate.expire(key, CACHE_EXPIRE_HOURS, TimeUnit.HOURS);
    }

    /**
     * 全量覆盖写入轮次列表缓存，按顺序追加并设置过期时间。
     */
    private void writeTurns(String sessionId, List<InterviewTurnLog> turns) {
        // （1）先删除旧列表
        String key = InterviewCacheKeys.turns(sessionId);
        stringRedisTemplate.delete(key);
        if (turns == null || turns.isEmpty()) {
            return;
        }
        // （2）逐条序列化后右追加，最后设置过期时间
        for (InterviewTurnLog turn : turns) {
            if (turn != null) {
                stringRedisTemplate.opsForList().rightPush(key, JSON.toJSONString(turn));
            }
        }
        stringRedisTemplate.expire(key, CACHE_EXPIRE_HOURS, TimeUnit.HOURS);
    }

    /**
     * 覆盖写入流程状态缓存，流程为空时仅清除旧值。
     */
    private void writeFlow(String sessionId, InterviewFlowState flow) {
        // （1）先删除旧值，流程为空时结束
        String key = InterviewCacheKeys.flow(sessionId);
        stringRedisTemplate.delete(key);
        if (flow == null) {
            return;
        }
        // （2）通过题目缓存服务恢复流程状态
        interviewQuestionCacheService.restoreInterviewFlow(sessionId, flow);
    }

    /**
     * 覆盖写入单值缓存，空值等同于清除。
     */
    private void writeValue(String key, Object value) {
        // （1）先删除旧值，值为空时结束
        stringRedisTemplate.delete(key);
        if (value == null) {
            return;
        }
        // （2）转为字符串后写入并设置过期时间
        String payload = String.valueOf(value);
        if (StrUtil.isBlank(payload)) {
            return;
        }
        stringRedisTemplate.opsForValue().set(key, payload, CACHE_EXPIRE_HOURS, TimeUnit.HOURS);
    }

    /**
     * 覆盖写入 JSON 单值缓存，空值等同于清除。
     */
    private void writeJsonValue(String key, Object value) {
        // （1）先删除旧值，值为空时结束
        stringRedisTemplate.delete(key);
        if (value == null) {
            return;
        }
        // （2）序列化为 JSON 后写入并设置过期时间
        stringRedisTemplate.opsForValue().set(key, JSON.toJSONString(value), CACHE_EXPIRE_HOURS, TimeUnit.HOURS);
    }

    /**
     * 写入神态评分明细：明细为空时清除相关的四项缓存。
     */
    private void writeDemeanorDetails(String sessionId, DemeanorScoreDTO detail) {
        // （1）明细为空时删除紧张度、认真度、表情处理与综合分缓存
        if (detail == null) {
            stringRedisTemplate.delete(InterviewCacheKeys.demeanorPanic(sessionId));
            stringRedisTemplate.delete(InterviewCacheKeys.demeanorSeriousness(sessionId));
            stringRedisTemplate.delete(InterviewCacheKeys.demeanorEmoticon(sessionId));
            stringRedisTemplate.delete(InterviewCacheKeys.demeanorComposite(sessionId));
            return;
        }
        // （2）逐项写入各维度分数
        writeValue(InterviewCacheKeys.demeanorPanic(sessionId), detail.getPanicLevel());
        writeValue(InterviewCacheKeys.demeanorSeriousness(sessionId), detail.getSeriousnessLevel());
        writeValue(InterviewCacheKeys.demeanorEmoticon(sessionId), detail.getEmoticonHandling());
        writeValue(InterviewCacheKeys.demeanorComposite(sessionId), detail.getCompositeScore());
    }

    /**
     * 写入分数汇总缓存：聚合值缺失时用现有总分或轮次数据推导。
     */
    private void writeScoreAggregate(String sessionId, InterviewRuntimeScoreAggregate aggregate, List<InterviewTurnLog> turns) {
        // （1）清除旧的分数相关缓存
        stringRedisTemplate.delete(InterviewCacheKeys.sessionScore(sessionId));
        stringRedisTemplate.delete(InterviewCacheKeys.sessionScoreSum(sessionId));
        stringRedisTemplate.delete(InterviewCacheKeys.sessionScoreCount(sessionId));
        // （2）解析总分、分数总和与计分题数，缺失时按轮次推导
        int totalScore = aggregate == null || aggregate.getTotalScore() == null
                ? interviewQuestionCacheService.getSessionTotalScore(sessionId)
                : aggregate.getTotalScore();
        int scoreSum = aggregate == null || aggregate.getScoreSum() == null ? deriveScoreSum(turns) : aggregate.getScoreSum();
        int scoreCount = aggregate == null || aggregate.getScoreCount() == null ? deriveScoreCount(turns) : aggregate.getScoreCount();
        // （3）写回三项分数缓存
        writeValue(InterviewCacheKeys.sessionScore(sessionId), totalScore);
        writeValue(InterviewCacheKeys.sessionScoreSum(sessionId), scoreSum);
        writeValue(InterviewCacheKeys.sessionScoreCount(sessionId), scoreCount);
    }

    /**
     * 用轮次中的请求 id 重建答卷与轮次两套幂等集合缓存。
     */
    private void writeRequestIds(String sessionId, List<InterviewTurnLog> turns) {
        // （1）清除旧的幂等集合
        String answerKey = InterviewCacheKeys.answerRequest(sessionId);
        String turnKey = InterviewCacheKeys.turnRequest(sessionId);
        stringRedisTemplate.delete(answerKey);
        stringRedisTemplate.delete(turnKey);
        if (turns == null || turns.isEmpty()) {
            return;
        }
        // （2）抽取去重后的非空请求 id
        List<String> requestIds = turns.stream()
                .map(InterviewTurnLog::getRequestId)
                .filter(StrUtil::isNotBlank)
                .map(String::trim)
                .distinct()
                .toList();
        if (requestIds.isEmpty()) {
            return;
        }
        // （3）写入两个集合并设置过期时间
        stringRedisTemplate.opsForSet().add(answerKey, requestIds.toArray(new String[0]));
        stringRedisTemplate.opsForSet().add(turnKey, requestIds.toArray(new String[0]));
        stringRedisTemplate.expire(answerKey, CACHE_EXPIRE_HOURS, TimeUnit.HOURS);
        stringRedisTemplate.expire(turnKey, CACHE_EXPIRE_HOURS, TimeUnit.HOURS);
    }

    /**
     * 统计主问题得分总和，追问与无分轮次不计入。
     */
    private int deriveScoreSum(List<InterviewTurnLog> turns) {
        // （1）轮次为空时总和为 0
        if (turns == null) {
            return 0;
        }
        // （2）累加有效主问题的分数
        int sum = 0;
        for (InterviewTurnLog turn : turns) {
            if (turn == null || turn.getScore() == null || Boolean.TRUE.equals(turn.getIsFollowUp())) {
                continue;
            }
            sum += turn.getScore();
        }
        return sum;
    }

    /**
     * 统计参与计分的主问题数量，追问与无分轮次不计入。
     */
    private int deriveScoreCount(List<InterviewTurnLog> turns) {
        // （1）轮次为空时数量为 0
        if (turns == null) {
            return 0;
        }
        // （2）累加有效主问题条数
        int count = 0;
        for (InterviewTurnLog turn : turns) {
            if (turn == null || turn.getScore() == null || Boolean.TRUE.equals(turn.getIsFollowUp())) {
                continue;
            }
            count++;
        }
        return count;
    }

    /**
     * 解析「编号 -> 内容」映射：优先解析 JSON，失败或为空时用列表按下标（从 1 开始）兜底。
     */
    private Map<String, String> parseStringMap(String jsonPayload, List<String> fallbackList) {
        // （1）优先尝试解析 JSON 字符串
        if (StrUtil.isNotBlank(jsonPayload)) {
            try {
                Map<String, String> parsed = JSON.parseObject(jsonPayload, new TypeReference<LinkedHashMap<String, String>>() {
                });
                if (parsed != null && !parsed.isEmpty()) {
                    return parsed;
                }
            } catch (Exception ignored) {
            }
        }
        // （2）JSON 不可用时用列表兜底，编号从 1 开始
        if (fallbackList == null || fallbackList.isEmpty()) {
            return new LinkedHashMap<>();
        }
        Map<String, String> mapped = new LinkedHashMap<>();
        for (int index = 0; index < fallbackList.size(); index++) {
            String value = fallbackList.get(index);
            if (StrUtil.isNotBlank(value)) {
                mapped.put(String.valueOf(index + 1), value);
            }
        }
        return mapped;
    }

    /**
     * 解析面试建议：优先取题目上的建议，缺失时从面试记录中的分号分隔文本兜底。
     */
    private Map<String, String> parseSuggestions(InterviewQuestion question, InterviewRecordDO record) {
        // （1）优先使用题目上的建议
        Map<String, String> suggestions = parseStringMap(
                question == null ? null : question.getSuggestionsJson(),
                question == null ? null : question.getSuggestions()
        );
        if (!suggestions.isEmpty()) {
            return suggestions;
        }
        // （2）题目无建议时解析记录中的分号分隔文本
        Map<String, String> parsedFromRecord = new LinkedHashMap<>();
        if (record == null || StrUtil.isBlank(record.getInterviewSuggestions())) {
            return parsedFromRecord;
        }
        String[] segments = record.getInterviewSuggestions().split(";");
        int order = 1;
        for (String segment : segments) {
            if (StrUtil.isNotBlank(segment)) {
                parsedFromRecord.put(String.valueOf(order++), segment.trim());
            }
        }
        return parsedFromRecord;
    }

    /**
     * 解析题目中保存的原始简历上下文 JSON，解析失败时返回空 Map。
     */
    private Map<String, Object> parseResumeContext(InterviewQuestion question) {
        // （1）题目或原始响应为空时返回空 Map
        if (question == null || StrUtil.isBlank(question.getRawResponseData())) {
            return new LinkedHashMap<>();
        }
        // （2）解析 JSON，异常时降级为空 Map
        try {
            Map<String, Object> parsed = JSON.parseObject(question.getRawResponseData(), new TypeReference<LinkedHashMap<String, Object>>() {
            });
            return parsed == null ? new LinkedHashMap<>() : parsed;
        } catch (Exception ex) {
            return new LinkedHashMap<>();
        }
    }

    /**
     * 解析简历评分：按快照、题目、面试记录的优先级取第一个可用值。
     */
    private Integer resolveResumeScore(
            InterviewQuestion question,
            InterviewSessionRuntimeSnapshot snapshot,
            InterviewRecordDO record) {
        // （1）快照优先，其次是题目，最后回落到记录
        if (snapshot != null && snapshot.getResumeScore() != null) {
            return snapshot.getResumeScore();
        }
        if (question != null && question.getResumeScore() != null) {
            return question.getResumeScore();
        }
        return record == null ? null : record.getResumeScore();
    }

    /**
     * 解析神态评分：优先取快照，缺失时从记录的会话快照 JSON 中读取雷达图分值。
     */
    private Integer resolveDemeanorScore(InterviewSessionRuntimeSnapshot snapshot, InterviewRecordDO record) {
        // （1）快照中已有神态分时直接返回
        if (snapshot != null && snapshot.getDemeanorScore() != null) {
            return snapshot.getDemeanorScore();
        }
        // （2）记录或会话快照 JSON 缺失时返回 null
        if (record == null || StrUtil.isBlank(record.getSessionSnapshotJson())) {
            return null;
        }
        // （3）从 radar.demeanorEvaluation 字段解析分数，异常时返回 null
        try {
            Map<String, Object> parsed = JSON.parseObject(record.getSessionSnapshotJson(), new TypeReference<LinkedHashMap<String, Object>>() {
            });
            Map<String, Object> radar = parsed == null ? null : asObjectMap(parsed.get("radar"));
            return radar == null ? null : asInteger(radar.get("demeanorEvaluation"));
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * 解析面试方向：按快照、题目、会话、记录的优先级取第一个非空值。
     */
    private String resolveDirection(
            InterviewSession session,
            InterviewQuestion question,
            InterviewSessionRuntimeSnapshot snapshot,
            InterviewRecordDO record) {
        // （1）按优先级依次尝试各来源的方向字段
        if (snapshot != null && StrUtil.isNotBlank(snapshot.getDirection())) {
            return snapshot.getDirection();
        }
        if (question != null && StrUtil.isNotBlank(question.getInterviewType())) {
            return question.getInterviewType().trim();
        }
        if (session != null && StrUtil.isNotBlank(session.getInterviewType())) {
            return session.getInterviewType().trim();
        }
        return record == null ? null : record.getInterviewDirection();
    }

    /**
     * 解析问答轮次：优先取已归档轮次，其次快照中的最近轮次，最后从记录快照 JSON 解析。
     */
    private List<InterviewTurnLog> resolveTurns(
            String sessionId,
            InterviewSessionRuntimeSnapshot snapshot,
            InterviewRecordDO record) {
        // （1）已归档轮次最完整，优先使用
        List<InterviewTurnLog> turns = runtimeSnapshotService.loadPersistedTurns(sessionId);
        if (turns != null && !turns.isEmpty()) {
            return new ArrayList<>(turns);
        }
        // （2）其次使用快照中的最近轮次
        if (snapshot != null && snapshot.getRecentTurns() != null && !snapshot.getRecentTurns().isEmpty()) {
            return new ArrayList<>(snapshot.getRecentTurns());
        }
        // （3）最后从记录快照 JSON 兜底解析
        return parseTurnsFromRecord(record);
    }

    /**
     * 从面试记录的会话快照 JSON 中解析轮次数组，失败时返回空列表。
     */
    private List<InterviewTurnLog> parseTurnsFromRecord(InterviewRecordDO record) {
        // （1）记录或会话快照 JSON 缺失时返回空列表
        if (record == null || StrUtil.isBlank(record.getSessionSnapshotJson())) {
            return new ArrayList<>();
        }
        // （2）解析 turns 字段并转换为轮次对象
        try {
            Map<String, Object> parsed = JSON.parseObject(record.getSessionSnapshotJson(), new TypeReference<LinkedHashMap<String, Object>>() {
            });
            Object turns = parsed == null ? null : parsed.get("turns");
            if (turns == null) {
                return new ArrayList<>();
            }
            List<InterviewTurnLog> parsedTurns = JSON.parseArray(JSON.toJSONString(turns), InterviewTurnLog.class);
            return parsedTurns == null ? new ArrayList<>() : new ArrayList<>(parsedTurns);
        } catch (Exception ex) {
            return new ArrayList<>();
        }
    }

    /**
     * 解析追问题目：优先取快照，缺失时从轮次的下一题字段中筛出题号带 "-F" 的追问。
     */
    private Map<String, String> deriveFollowUpQuestions(
            InterviewSessionRuntimeSnapshot snapshot,
            List<InterviewTurnLog> turns) {
        // （1）快照中已有追问时直接返回其副本
        if (snapshot != null && snapshot.getFollowUpQuestions() != null && !snapshot.getFollowUpQuestions().isEmpty()) {
            return new LinkedHashMap<>(snapshot.getFollowUpQuestions());
        }
        // （2）从轮次中提取追问题号与内容
        Map<String, String> followUpQuestions = new LinkedHashMap<>();
        if (turns == null) {
            return followUpQuestions;
        }
        for (InterviewTurnLog turn : turns) {
            if (turn == null || StrUtil.isBlank(turn.getNextQuestionNumber()) || StrUtil.isBlank(turn.getNextQuestion())) {
                continue;
            }
            String questionNumber = normalizeQuestionNumber(turn.getNextQuestionNumber());
            if (questionNumber != null && questionNumber.contains("-F")) {
                followUpQuestions.put(questionNumber, turn.getNextQuestion().trim());
            }
        }
        return followUpQuestions;
    }

    /**
     * 解析分数汇总：优先取快照，缺失时按轮次统计并结合记录总分推导。
     */
    private InterviewRuntimeScoreAggregate resolveScoreAggregate(
            InterviewSessionRuntimeSnapshot snapshot,
            List<InterviewTurnLog> turns,
            InterviewRecordDO record) {
        // （1）快照中已有汇总时直接返回
        if (snapshot != null && snapshot.getScoreAggregate() != null) {
            return snapshot.getScoreAggregate();
        }
        // （2）用轮次统计分数总和与计分题数
        InterviewRuntimeScoreAggregate aggregate = new InterviewRuntimeScoreAggregate();
        aggregate.setScoreSum(deriveScoreSum(turns));
        aggregate.setScoreCount(deriveScoreCount(turns));
        // （3）总分优先取记录；否则按平均分计算，无数据时为 0
        if (record != null && record.getInterviewScore() != null) {
            aggregate.setTotalScore(record.getInterviewScore());
        } else if (aggregate.getScoreCount() != null && aggregate.getScoreCount() > 0) {
            aggregate.setTotalScore((int) Math.round((double) aggregate.getScoreSum() / aggregate.getScoreCount()));
        } else {
            aggregate.setTotalScore(0);
        }
        return aggregate;
    }

    /**
     * 解析流程状态：优先取快照，其次按会话终态、轮次与题目数推导。
     */
    private InterviewFlowState resolveFlow(
            InterviewSession session,
            Map<String, String> questions,
            List<InterviewTurnLog> turns,
            InterviewSessionRuntimeSnapshot snapshot) {
        // （1）快照中已有流程时直接返回
        if (snapshot != null && snapshot.getFlow() != null) {
            return snapshot.getFlow();
        }
        // （2）会话已结束时直接构造完成态流程
        int totalQuestions = questions == null ? 0 : questions.size();
        if (isTerminalSession(session)) {
            return buildCompletedFlow(totalQuestions);
        }
        // （3）无轮次时按初始状态或空流程处理
        if (turns == null || turns.isEmpty()) {
            return totalQuestions > 0 ? buildInitialFlow(totalQuestions) : null;
        }
        // （4）以最新轮次判断是否已结束或推进到下一题
        InterviewTurnLog latest = turns.get(turns.size() - 1);
        if (latest == null || Boolean.TRUE.equals(latest.getFinished())) {
            return buildCompletedFlow(totalQuestions);
        }
        String nextQuestionNumber = normalizeQuestionNumber(latest.getNextQuestionNumber());
        if (StrUtil.isBlank(nextQuestionNumber)) {
            return totalQuestions > 0 ? buildInitialFlow(totalQuestions) : null;
        }
        return buildFlow(nextQuestionNumber, totalQuestions);
    }

    /**
     * 评估本次恢复的置信度：终态会话最高，题目与流程齐全为推导态，否则只读。
     */
    private InterviewRuntimeConfidence resolveConfidence(
            InterviewSession session,
            Map<String, String> questions,
            InterviewFlowState flow) {
        // （1）终态会话置信度为 TERMINAL
        if (isTerminalSession(session)) {
            return InterviewRuntimeConfidence.TERMINAL;
        }
        // （2）有题目材料时视为推导态，否则只读
        if (hasQuestionMaterial(questions) && flow != null) {
            return InterviewRuntimeConfidence.DERIVED;
        }
        return hasQuestionMaterial(questions) ? InterviewRuntimeConfidence.DERIVED : InterviewRuntimeConfidence.READ_ONLY;
    }

    /**
     * 判定恢复来源：有题目数据用 SESSION_QUESTION，有记录用 INTERVIEW_RECORD，否则 NONE。
     */
    private InterviewRuntimeRestoreSource resolveRestoreSource(InterviewRecordDO record, InterviewQuestion question) {
        // （1）按题目、记录、无来源的顺序判定
        if (question != null) {
            return InterviewRuntimeRestoreSource.SESSION_QUESTION;
        }
        if (record != null) {
            return InterviewRuntimeRestoreSource.INTERVIEW_RECORD;
        }
        return InterviewRuntimeRestoreSource.NONE;
    }

    private boolean hasQuestionMaterial(Map<String, String> questions) {
        return questions != null && !questions.isEmpty();
    }

    /**
     * 判断会话是否已处于终态（非活跃状态），状态非法或缺失时视为非终态。
     */
    private boolean isTerminalSession(InterviewSession session) {
        // （1）会话或状态缺失时按非终态处理
        if (session == null || StrUtil.isBlank(session.getStatus())) {
            return false;
        }
        // （2）解析状态枚举，非活跃即视为终态；解析失败按非终态处理
        try {
            InterviewSessionStatus status = InterviewSessionStatus.valueOf(session.getStatus());
            return !status.isActive();
        } catch (Exception ex) {
            return false;
        }
    }

    /**
     * 构造初始流程：处于提问态、下标为 0、指向第 1 题。
     */
    private InterviewFlowState buildInitialFlow(int totalQuestions) {
        // （1）填充初始流程字段
        InterviewFlowState flow = new InterviewFlowState();
        flow.setStatus("ASKING");
        flow.setCurrentIndex(0);
        flow.setCurrentQuestionNumber(totalQuestions > 0 ? "1" : null);
        flow.setTotalQuestions(totalQuestions);
        flow.setFollowUpCount(0);
        flow.setMaxFollowUp(2);
        flow.setVersion(1);
        return flow;
    }

    /**
     * 构造完成态流程：下标等于题目总数、无当前题号。
     */
    private InterviewFlowState buildCompletedFlow(int totalQuestions) {
        // （1）填充完成态流程字段
        InterviewFlowState flow = new InterviewFlowState();
        flow.setStatus("COMPLETED");
        flow.setCurrentIndex(Math.max(totalQuestions, 0));
        flow.setCurrentQuestionNumber(null);
        flow.setTotalQuestions(Math.max(totalQuestions, 0));
        flow.setFollowUpCount(0);
        flow.setMaxFollowUp(2);
        flow.setVersion(1);
        return flow;
    }

    /**
     * 按题号构造流程状态：判断是否为追问，并推导当前下标与追问次数。
     */
    private InterviewFlowState buildFlow(String questionNumber, int totalQuestions) {
        // （1）解析主问题序号并判断状态是提问还是追问
        InterviewFlowState flow = new InterviewFlowState();
        Integer mainQuestionNo = extractMainQuestionNo(questionNumber);
        flow.setStatus(questionNumber != null && questionNumber.contains("-F") ? "FOLLOW_UP" : "ASKING");
        // （2）填充下标、题号、题目总数与追问次数
        flow.setCurrentIndex(mainQuestionNo == null ? 0 : Math.max(mainQuestionNo - 1, 0));
        flow.setCurrentQuestionNumber(normalizeQuestionNumber(questionNumber));
        flow.setTotalQuestions(Math.max(totalQuestions, 0));
        flow.setFollowUpCount(resolveFollowUpCount(questionNumber));
        flow.setMaxFollowUp(2);
        flow.setVersion(1);
        return flow;
    }

    /**
     * 从题号中提取主问题序号（追问 "1-F1" 取主问题 1）。
     */
    private Integer extractMainQuestionNo(String questionNumber) {
        // （1）归一化题号，空值返回 null
        String normalized = normalizeQuestionNumber(questionNumber);
        if (StrUtil.isBlank(normalized)) {
            return null;
        }
        // （2）截取 "-F" 之前的作为主问题号并解析
        int separatorIndex = normalized.indexOf("-F");
        String mainPart = separatorIndex > 0 ? normalized.substring(0, separatorIndex) : normalized;
        try {
            return Integer.parseInt(mainPart);
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * 解析追问序号（"1-F2" 返回 2），非追问题号返回 0。
     */
    private int resolveFollowUpCount(String questionNumber) {
        // （1）非追问格式直接返回 0
        if (StrUtil.isBlank(questionNumber) || !questionNumber.toUpperCase().matches("\\d+-F\\d+")) {
            return 0;
        }
        // （2）截取 "-F" 之后的数字作为追问序号
        int separatorIndex = questionNumber.indexOf("-F");
        try {
            return Math.max(Integer.parseInt(questionNumber.substring(separatorIndex + 2).trim()), 0);
        } catch (Exception ex) {
            return 0;
        }
    }

    /**
     * 归一化题号：统一大写、去除前导零，保留 "主问题-F追问" 格式。
     */
    private String normalizeQuestionNumber(String questionNumber) {
        // （1）空值返回 null，并统一去空格转大写
        if (StrUtil.isBlank(questionNumber)) {
            return null;
        }
        String normalized = questionNumber.trim().toUpperCase();
        // （2）纯数字题号去除前导零
        if (normalized.matches("\\d+")) {
            try {
                return String.valueOf(Integer.parseInt(normalized));
            } catch (Exception ex) {
                return normalized;
            }
        }
        // （3）追问格式分别规范化主问题号与追问序号
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
     * 把任意对象转换为键为字符串的 Map，非 Map 对象先转 JSON 再解析。
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> asObjectMap(Object value) {
        // （1）本身是 Map 时逐项转换键为字符串
        if (value instanceof Map<?, ?> mapValue) {
            Map<String, Object> mapped = new LinkedHashMap<>();
            mapValue.forEach((key, item) -> {
                if (key != null) {
                    mapped.put(String.valueOf(key), item);
                }
            });
            return mapped;
        }
        // （2）其他对象先序列化再解析为 Map，null 返回 null
        return value == null ? null : JSON.parseObject(JSON.toJSONString(value), new TypeReference<LinkedHashMap<String, Object>>() {
        });
    }

    /** 把对象转换为 Integer，值为空或无法解析时返回 null。 */
    private Integer asInteger(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception ex) {
            return null;
        }
    }

    /**
     * 运行态重建过程中的临时材料聚合对象，汇总从各数据源解析出的可回写内容。
     */
    private static final class RuntimeMaterial {
        // 题目映射（题号 -> 题干）
        private Map<String, String> questions = new LinkedHashMap<>();
        // 面试建议映射
        private Map<String, String> suggestions = new LinkedHashMap<>();
        // 简历上下文
        private Map<String, Object> resumeContext = new LinkedHashMap<>();
        // 简历评分
        private Integer resumeScore;
        // 神态评分
        private Integer demeanorScore;
        // 神态评分明细
        private DemeanorScoreDTO demeanorDetails;
        // 面试方向
        private String direction;
        // 流程状态
        private InterviewFlowState flow;
        // 分数汇总
        private InterviewRuntimeScoreAggregate scoreAggregate;
        // 追问映射（追问题号 -> 题干）
        private Map<String, String> followUpQuestions = new LinkedHashMap<>();
        // 问答轮次
        private List<InterviewTurnLog> turns = new ArrayList<>();
        // 本次重建结果的置信度
        private InterviewRuntimeConfidence confidence = InterviewRuntimeConfidence.READ_ONLY;
    }
}
