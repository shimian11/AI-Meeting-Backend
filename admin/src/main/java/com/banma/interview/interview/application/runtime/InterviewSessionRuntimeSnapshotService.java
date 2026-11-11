package com.banma.interview.interview.application.runtime;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.TypeReference;
import com.banma.interview.interview.api.io.resp.InterviewAnswerRespDTO;
import com.banma.interview.interview.dao.entity.InterviewQuestion;
import com.banma.interview.interview.dao.entity.InterviewSession;
import com.banma.interview.interview.dao.entity.InterviewSessionRuntimeColdSnapshot;
import com.banma.interview.interview.dao.entity.InterviewSessionRuntimeHotSnapshot;
import com.banma.interview.interview.dao.entity.InterviewSessionRuntimeSnapshot;
import com.banma.interview.interview.dao.entity.InterviewSessionTurnArchive;
import com.banma.interview.interview.dao.repository.InterviewSessionRuntimeColdSnapshotRepository;
import com.banma.interview.interview.dao.repository.InterviewSessionRuntimeHotSnapshotRepository;
import com.banma.interview.interview.dao.repository.InterviewSessionTurnArchiveRepository;
import com.banma.interview.interview.service.InterviewQuestionCacheService;
import com.banma.interview.interview.service.InterviewQuestionService;
import com.banma.interview.interview.service.InterviewSessionService;
import com.banma.interview.interview.service.model.InterviewFlowState;
import com.banma.interview.interview.service.model.InterviewRuntimeConfidence;
import com.banma.interview.interview.service.model.InterviewRuntimeScoreAggregate;
import com.banma.interview.interview.service.model.InterviewSessionStatus;
import com.banma.interview.interview.service.model.InterviewTurnLog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 提供面试会话运行态快照与轮次归档的持久化能力，
 * 用于在关键业务节点保存恢复材料并支持软回放幂等与会话重建。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class InterviewSessionRuntimeSnapshotService {

    /** 快照中保留的最近轮次上限，防止文档无限膨胀 */
    private static final int RECENT_TURN_LIMIT = 20;
    /** 热快照 CAS 写入的最大重试次数 */
    private static final int HOT_SNAPSHOT_CAS_MAX_RETRIES = 3;
    /** CAS 重试的退避基数（毫秒） */
    private static final long HOT_SNAPSHOT_CAS_BASE_BACKOFF_MILLIS = 20L;

    /** 运行态热快照仓储 */
    private final InterviewSessionRuntimeHotSnapshotRepository hotSnapshotRepository;
    /** 运行态冷快照仓储 */
    private final InterviewSessionRuntimeColdSnapshotRepository coldSnapshotRepository;
    /** 轮次归档仓储 */
    private final InterviewSessionTurnArchiveRepository turnArchiveRepository;
    /** 面试会话持久化服务（Mongo） */
    private final InterviewSessionService interviewSessionService;
    /** 面试题目持久化服务（MySQL） */
    private final InterviewQuestionService interviewQuestionService;
    /** 题目、流程与得分的 Redis 缓存读写 */
    private final InterviewQuestionCacheService interviewQuestionCacheService;

    @Lazy
    @Autowired
    private InterviewSessionRuntimeHotRefreshCoordinator hotRefreshCoordinator;

    /**
     * 初始化草稿快照：为新建会话写入基础热/冷快照，已存在的内容保持不变。
     */
    public void initializeDraftSnapshot(InterviewSession session) {
        // （1）会话或 sessionId 为空时跳过
        if (session == null || StrUtil.isBlank(session.getSessionId())) {
            return;
        }
        try {
            // （2）读取现有冷热快照，用于沿用已有内容与版本号
            InterviewSessionRuntimeHotSnapshot hotSnapshot = findHotSnapshot(session.getSessionId()).orElse(null);
            InterviewSessionRuntimeColdSnapshot coldSnapshot = findColdSnapshot(session.getSessionId()).orElse(null);
            Date now = new Date();
            // （3）写入热快照：版本自增，初始级别为 DRAFT
            hotSnapshotRepository.applyPatch(session.getSessionId(), InterviewSessionRuntimeHotPatch.builder()
                    .userId(session.getUserId())
                    .sessionStatus(session.getStatus())
                    .snapshotVersion(nextVersion(hotSnapshot == null ? null : hotSnapshot.getSnapshotVersion()))
                    .snapshotLevel("DRAFT")
                    .rebuildConfidence(InterviewRuntimeConfidence.READ_ONLY)
                    .snapshotUpdatedAt(now)
                    .scoreAggregate(hotSnapshot != null && hotSnapshot.getScoreAggregate() != null ? hotSnapshot.getScoreAggregate() : emptyScoreAggregate())
                    .recentTurns(hotSnapshot != null && hotSnapshot.getRecentTurns() != null ? hotSnapshot.getRecentTurns() : new ArrayList<>())
                    .recentTurnCount(hotSnapshot != null && hotSnapshot.getRecentTurnCount() != null ? hotSnapshot.getRecentTurnCount() : 0)
                    .lastTurnSeq(hotSnapshot != null && hotSnapshot.getLastTurnSeq() != null ? hotSnapshot.getLastTurnSeq() : 0L)
                    .build());
            // （4）写入冷快照：简历地址与面试方向等材料信息
            coldSnapshotRepository.applyPatch(session.getSessionId(), InterviewSessionRuntimeColdPatch.builder()
                    .userId(session.getUserId())
                    .materialVersion(nextVersion(coldSnapshot == null ? null : coldSnapshot.getMaterialVersion()))
                    .resumeFileUrl(session.getResumeFileUrl())
                    .interviewType(session.getInterviewType())
                    .materialUpdatedAt(now)
                    .build());
        } catch (Exception ex) {
            // （5）初始化失败只记录告警，不影响主流程
            log.warn("Failed to initialize runtime snapshot, sessionId={}", session.getSessionId(), ex);
        }
    }

    /**
     * 查询会话运行态快照（冷热合并后的视图）。
     */
    public Optional<InterviewSessionRuntimeSnapshot> findSnapshot(String sessionId) {
        // （1）sessionId 为空时返回空
        if (StrUtil.isBlank(sessionId)) {
            return Optional.empty();
        }
        // （2）分别读取冷热快照并合并
        InterviewSessionRuntimeHotSnapshot hotSnapshot = findHotSnapshot(sessionId).orElse(null);
        InterviewSessionRuntimeColdSnapshot coldSnapshot = findColdSnapshot(sessionId).orElse(null);
        return Optional.ofNullable(assembleSnapshot(hotSnapshot, coldSnapshot));
    }

    /**
     * 查询会话热快照（流程、分数、最近轮次等高频数据）。
     */
    public Optional<InterviewSessionRuntimeHotSnapshot> findHotSnapshot(String sessionId) {
        // （1）sessionId 为空时返回空
        if (StrUtil.isBlank(sessionId)) {
            return Optional.empty();
        }
        return hotSnapshotRepository.findBySessionId(sessionId);
    }

    /**
     * 查询会话冷快照（题目、建议、简历等低频材料）。
     */
    public Optional<InterviewSessionRuntimeColdSnapshot> findColdSnapshot(String sessionId) {
        // （1）sessionId 为空时返回空
        if (StrUtil.isBlank(sessionId)) {
            return Optional.empty();
        }
        return coldSnapshotRepository.findBySessionId(sessionId);
    }

    /** 题目抽取完成后提交一次热刷新（普通中间态）。 */
    public void refreshAfterQuestionExtraction(String sessionId) {
        hotRefreshCoordinator.submit(InterviewSessionRuntimeHotRefreshRequest.questionReady(sessionId));
    }

    /** 神态评估完成后提交一次热刷新（普通中间态）。 */
    public void refreshAfterDemeanorEvaluated(String sessionId) {
        hotRefreshCoordinator.submit(InterviewSessionRuntimeHotRefreshRequest.demeanorReady(sessionId));
    }

    /** 答案提交后提交一次强制刷新，并归档本轮问答。 */
    public void refreshAfterAnswerCommitted(String sessionId, String requestId, InterviewTurnLog turnLog) {
        hotRefreshCoordinator.submit(InterviewSessionRuntimeHotRefreshRequest.answerCommitted(sessionId, requestId, turnLog));
    }

    /** 面试收尾后提交一次强制刷新。 */
    public void refreshAfterFinalize(String sessionId) {
        hotRefreshCoordinator.submit(InterviewSessionRuntimeHotRefreshRequest.finalized(sessionId));
    }

    /**
     * 执行一次热刷新落盘。
     *
     * @return 落盘成功返回 true，请求为空或落盘失败返回 false
     */
    public boolean flushHotRefreshRequest(InterviewSessionRuntimeHotRefreshRequest request) {
        // （1）请求为空时直接返回失败
        if (request == null) {
            return false;
        }
        // （2）按请求字段刷新快照
        return refreshSnapshot(
                request.getSessionId(),
                request.getSnapshotLevel(),
                request.getRequestId(),
                request.getCommittedTurn(),
                request.isPersistTurnArchive()
        );
    }

    /**
     * 加载会话已持久化的轮次：优先取归档表，其次取缓存，最后取快照中的最近轮次。
     */
    public List<InterviewTurnLog> loadPersistedTurns(String sessionId) {
        // （1）sessionId 为空时返回空列表
        if (StrUtil.isBlank(sessionId)) {
            return new ArrayList<>();
        }
        // （2）优先使用轮次归档表
        List<InterviewSessionTurnArchive> archives = loadTurnArchives(sessionId);
        if (archives != null && !archives.isEmpty()) {
            return archives.stream()
                    .map(InterviewSessionTurnArchive::getTurnPayload)
                    .filter(item -> item != null)
                    .collect(Collectors.toCollection(ArrayList::new));
        }
        // （3）其次使用缓存中的轮次
        List<InterviewTurnLog> cacheTurns = interviewQuestionCacheService.getInterviewTurns(sessionId);
        if (cacheTurns != null && !cacheTurns.isEmpty()) {
            return cacheTurns;
        }
        // （4）最后使用快照中的最近轮次
        return findSnapshot(sessionId)
                .map(InterviewSessionRuntimeSnapshot::getRecentTurns)
                .map(ArrayList::new)
                .orElseGet(ArrayList::new);
    }

    /**
     * 查找可回放的答卷响应，用于请求重复提交时的幂等返回。
     *
     * @return 命中的响应，未命中时返回 null
     */
    public InterviewAnswerRespDTO findReplayResponse(
            String sessionId,
            String requestId,
            String questionNumber,
            String answerContent) {
        // （1）sessionId 为空时返回 null
        if (StrUtil.isBlank(sessionId)) {
            return null;
        }
        // （2）读取快照，缺失时无法回放
        InterviewSessionRuntimeSnapshot snapshot = findSnapshot(sessionId).orElse(null);
        if (snapshot == null) {
            return null;
        }
        // （3）匹配历史轮次并构造回放响应
        InterviewTurnLog matchedTurn = findReplayTurn(snapshot, requestId, questionNumber, answerContent);
        return matchedTurn == null ? null : buildReplayResponse(matchedTurn);
    }

    /**
     * 按序号升序加载会话的全部轮次归档。
     */
    public List<InterviewSessionTurnArchive> loadTurnArchives(String sessionId) {
        // （1）sessionId 为空时返回空列表
        if (sessionId == null || sessionId.isBlank()) {
            return Collections.emptyList();
        }
        return turnArchiveRepository.findBySessionIdOrderBySeqAsc(sessionId);
    }

    /**
     * 按请求 id 查询单条轮次归档，用于判断该请求是否已提交过。
     */
    public Optional<InterviewSessionTurnArchive> findTurnArchive(String sessionId, String requestId) {
        // （1）参数为空时返回空
        if (sessionId == null || sessionId.isBlank() || requestId == null || requestId.isBlank()) {
            return Optional.empty();
        }
        return turnArchiveRepository.findBySessionIdAndRequestId(sessionId, requestId);
    }

    /**
     * 刷新运行态快照：先按 CAS 更新热快照，再按需补写冷快照材料。
     *
     * @param snapshotLevel 本次写入的快照级别
     * @param requestId 请求幂等 id，用于判断变更是否已应用
     * @param committedTurn 本轮待提交的问答
     * @param persistTurnArchive 是否需要把轮次写入归档表
     * @return 刷新成功（含幂等跳过）返回 true，失败返回 false
     */
    private boolean refreshSnapshot(
            String sessionId,
            String snapshotLevel,
            String requestId,
            InterviewTurnLog committedTurn,
            boolean persistTurnArchive) {
        // （1）sessionId 为空时直接返回失败
        if (StrUtil.isBlank(sessionId)) {
            return false;
        }
        try {
            // （2）读取会话与题目主数据，会话缺失时无法刷新
            InterviewSession session = interviewSessionService.getBySessionId(sessionId);
            if (session == null) {
                return false;
            }
            InterviewQuestion question = interviewQuestionService.getBySessionId(sessionId);
            // （3）按最大重试次数执行 CAS 更新
            for (int attempt = 0; attempt < HOT_SNAPSHOT_CAS_MAX_RETRIES; attempt++) {
                InterviewSessionRuntimeHotSnapshot hotSnapshot = findHotSnapshot(sessionId).orElse(null);
                InterviewSessionRuntimeColdSnapshot coldSnapshot = findColdSnapshot(sessionId).orElse(null);
                InterviewSessionRuntimeSnapshot snapshot = assembleSnapshot(hotSnapshot, coldSnapshot);

                // （4）解析本次要写入的题目、建议、轮次、流程与分数等数据
                Map<String, String> questions = resolveQuestions(sessionId, question);
                Map<String, String> suggestions = resolveSuggestions(sessionId, question);
                Map<String, Object> resumeContext = resolveResumeContext(sessionId, question);
                Long archiveWatermark = persistTurnArchive
                        ? archiveTurn(sessionId, requestId, committedTurn, hotSnapshot == null ? null : hotSnapshot.getSnapshotVersion())
                        : resolveArchiveWatermark(hotSnapshot == null ? null : hotSnapshot.getArchiveWatermark(), sessionId);
                List<InterviewTurnLog> turns = resolveTurns(sessionId, snapshot, committedTurn);
                InterviewFlowState flow = resolveFlow(sessionId, session, questions, turns);
                InterviewRuntimeScoreAggregate scoreAggregate = resolveScoreAggregate(sessionId, turns);
                List<InterviewTurnLog> recentTurns = limitRecentTurns(turns);
                Date now = new Date();
                String mutationId = normalizeMutationId(requestId);
                InterviewSessionRuntimeHotPatch hotPatch = buildHotPatch(
                        session,
                        snapshotLevel,
                        mutationId,
                        committedTurn,
                        questions,
                        turns,
                        flow,
                        scoreAggregate,
                        recentTurns,
                        archiveWatermark,
                        hotSnapshot,
                        now
                );

                // （5）热快照内容无变化时只按需补写冷快照
                if (shouldSkipHotPatch(hotSnapshot, hotPatch)) {
                    applyColdSnapshotIfNecessary(sessionId, snapshotLevel, session, question, coldSnapshot, questions, suggestions, resumeContext, now);
                    return true;
                }

                // （6）热快照不存在时先播种一条，再进入下一轮 CAS
                if (hotSnapshot == null || hotSnapshot.getSnapshotVersion() == null) {
                    seedHotSnapshot(sessionId, session, snapshotLevel, now);
                    applyColdSnapshotIfNecessary(sessionId, snapshotLevel, session, question, coldSnapshot, questions, suggestions, resumeContext, now);
                    continue;
                }

                // （7）同一 mutation 已应用时直接返回，保证刷新幂等
                if (isMutationAlreadyApplied(hotSnapshot, mutationId)) {
                    applyColdSnapshotIfNecessary(sessionId, snapshotLevel, session, question, coldSnapshot, questions, suggestions, resumeContext, now);
                    return true;
                }

                // （8）校验关键字段不回退后执行 CAS 更新
                validateHotPatchMonotonicity(hotSnapshot, hotPatch);

                boolean updated = hotSnapshotRepository.compareAndSetPatch(sessionId, hotSnapshot.getSnapshotVersion(), hotPatch);
                if (updated) {
                    applyColdSnapshotIfNecessary(sessionId, snapshotLevel, session, question, coldSnapshot, questions, suggestions, resumeContext, now);
                    return true;
                }

                // （9）CAS 失败后复查是否已被并发请求写入同一变更
                InterviewSessionRuntimeHotSnapshot latestSnapshot = findHotSnapshot(sessionId).orElse(null);
                if (isMutationAlreadyApplied(latestSnapshot, mutationId)) {
                    applyColdSnapshotIfNecessary(sessionId, snapshotLevel, session, question, coldSnapshot, questions, suggestions, resumeContext, now);
                    return true;
                }

                // （10）退避后进行下一轮重试
                backoffHotSnapshotRetry(attempt);
            }
            // （11）重试次数耗尽，记录告警并返回失败
            log.warn("Hot snapshot CAS retries exhausted, sessionId={}, snapshotLevel={}, requestId={}",
                    sessionId, snapshotLevel, requestId);
            return false;
        } catch (Exception ex) {
            // （12）异常时记录告警并返回失败，不影响调用方主流程
            log.warn("Failed to refresh runtime snapshot, sessionId={}", sessionId, ex);
            return false;
        }
    }

    /**
     * 组装热快照补丁：写入状态、流程、分数、最近轮次与幂等标记。
     */
    private InterviewSessionRuntimeHotPatch buildHotPatch(
            InterviewSession session,
            String snapshotLevel,
            String mutationId,
            InterviewTurnLog committedTurn,
            Map<String, String> questions,
            List<InterviewTurnLog> turns,
            InterviewFlowState flow,
            InterviewRuntimeScoreAggregate scoreAggregate,
            List<InterviewTurnLog> recentTurns,
            Long archiveWatermark,
            InterviewSessionRuntimeHotSnapshot hotSnapshot,
            Date now) {
        // （1）按会话现状与解析结果构建补丁，并带上版本与幂等标记
        return InterviewSessionRuntimeHotPatch.builder()
                .userId(session.getUserId())
                .sessionStatus(session.getStatus())
                .snapshotVersion(nextVersion(hotSnapshot == null ? null : hotSnapshot.getSnapshotVersion()))
                .snapshotLevel(snapshotLevel)
                .rebuildConfidence(resolveConfidence(session, questions, flow))
                .snapshotUpdatedAt(now)
                .flow(flow)
                .scoreAggregate(scoreAggregate)
                .followUpQuestions(resolveFollowUpQuestions(session.getSessionId(), turns))
                .recentTurns(recentTurns)
                .recentTurnCount(recentTurns.size())
                .archiveWatermark(archiveWatermark)
                .lastTurnSeq(archiveWatermark)
                .lastAppliedRequestId(mutationId)
                .lastMutationId(mutationId)
                .lastMutationTime(StrUtil.isBlank(mutationId) ? null : now)
                .lastCommittedQuestionNumber(committedTurn == null ? null : normalizeQuestionNumber(committedTurn.getQuestionNumber()))
                .lastCommittedTurnDigest(committedTurn == null ? null : buildTurnDigest(committedTurn.getQuestionNumber(), committedTurn.getAnswerContent()))
                .build();
    }

    /**
     * 按需补写冷快照：ACTIVE 级别在冷快照已存在时跳过，其余情况刷新材料信息。
     */
    private void applyColdSnapshotIfNecessary(
            String sessionId,
            String snapshotLevel,
            InterviewSession session,
            InterviewQuestion question,
            InterviewSessionRuntimeColdSnapshot coldSnapshot,
            Map<String, String> questions,
            Map<String, String> suggestions,
            Map<String, Object> resumeContext,
            Date now) {
        // （1）ACTIVE 级别且冷快照已存在时无需重复写入
        if (coldSnapshot != null && StrUtil.equals("ACTIVE", snapshotLevel)) {
            return;
        }
        // （2）刷新冷快照中的材料与神态信息，版本号自增
        coldSnapshotRepository.applyPatch(sessionId, InterviewSessionRuntimeColdPatch.builder()
                .userId(session.getUserId())
                .materialVersion(nextVersion(coldSnapshot == null ? null : coldSnapshot.getMaterialVersion()))
                .resumeFileUrl(session.getResumeFileUrl())
                .interviewType(resolveInterviewType(session, question, coldSnapshot))
                .direction(resolveDirection(sessionId, session, question))
                .questions(questions)
                .suggestions(suggestions)
                .resumeContext(resumeContext)
                .resumeScore(resolveResumeScore(sessionId, question))
                .demeanorScore(interviewQuestionCacheService.getSessionDemeanorScore(sessionId))
                .demeanorDetails(interviewQuestionCacheService.getSessionDemeanorScoreDetails(sessionId))
                .materialUpdatedAt(now)
                .build());
    }

    /**
     * 首次播种热快照：版本置 1，流程与轮次留空，水位线取历史归档最大值。
     */
    private void seedHotSnapshot(String sessionId, InterviewSession session, String snapshotLevel, Date now) {
        // （1）写入基础热快照，便于后续 CAS 继续更新
        hotSnapshotRepository.applyPatch(sessionId, InterviewSessionRuntimeHotPatch.builder()
                .userId(session.getUserId())
                .sessionStatus(session.getStatus())
                .snapshotVersion(1L)
                .snapshotLevel(snapshotLevel)
                .rebuildConfidence(InterviewRuntimeConfidence.READ_ONLY)
                .snapshotUpdatedAt(now)
                .scoreAggregate(emptyScoreAggregate())
                .recentTurns(new ArrayList<>())
                .recentTurnCount(0)
                .archiveWatermark(resolveArchiveWatermark(null, sessionId))
                .lastTurnSeq(0L)
                .build());
    }

    /**
     * 判断该 mutation 是否已写入热快照，用于保证刷新幂等。
     */
    private boolean isMutationAlreadyApplied(InterviewSessionRuntimeHotSnapshot hotSnapshot, String mutationId) {
        // （1）快照或 mutationId 缺失时视为未应用
        if (hotSnapshot == null || StrUtil.isBlank(mutationId)) {
            return false;
        }
        // （2）比较快照记录的最近 mutationId
        return StrUtil.equals(StrUtil.trim(hotSnapshot.getLastMutationId()), mutationId);
    }

    /**
     * 判断热快照与补丁内容是否完全一致，一致则跳过写库。
     */
    private boolean shouldSkipHotPatch(InterviewSessionRuntimeHotSnapshot hotSnapshot, InterviewSessionRuntimeHotPatch hotPatch) {
        // （1）任一为空时不跳过
        if (hotSnapshot == null || hotPatch == null) {
            return false;
        }
        // （2）逐字段比较关键内容
        return Objects.equals(hotSnapshot.getUserId(), hotPatch.getUserId())
                && StrUtil.equals(hotSnapshot.getSessionStatus(), hotPatch.getSessionStatus())
                && StrUtil.equals(hotSnapshot.getSnapshotLevel(), hotPatch.getSnapshotLevel())
                && hotSnapshot.getRebuildConfidence() == hotPatch.getRebuildConfidence()
                && Objects.equals(hotSnapshot.getFlow(), hotPatch.getFlow())
                && Objects.equals(hotSnapshot.getScoreAggregate(), hotPatch.getScoreAggregate())
                && Objects.equals(hotSnapshot.getFollowUpQuestions(), hotPatch.getFollowUpQuestions())
                && Objects.equals(hotSnapshot.getRecentTurns(), hotPatch.getRecentTurns())
                && Objects.equals(hotSnapshot.getRecentTurnCount(), hotPatch.getRecentTurnCount())
                && Objects.equals(hotSnapshot.getArchiveWatermark(), hotPatch.getArchiveWatermark())
                && Objects.equals(hotSnapshot.getLastTurnSeq(), hotPatch.getLastTurnSeq())
                && StrUtil.equals(hotSnapshot.getLastAppliedRequestId(), hotPatch.getLastAppliedRequestId())
                && StrUtil.equals(hotSnapshot.getLastMutationId(), hotPatch.getLastMutationId())
                && StrUtil.equals(hotSnapshot.getLastCommittedQuestionNumber(), hotPatch.getLastCommittedQuestionNumber())
                && StrUtil.equals(hotSnapshot.getLastCommittedTurnDigest(), hotPatch.getLastCommittedTurnDigest());
    }

    /**
     * 校验热快照补丁的单调性，禁止轮次水位、计分题数与流程下标回退。
     *
     * @throws IllegalStateException 任一关键字段回退时抛出
     */
    private void validateHotPatchMonotonicity(
            InterviewSessionRuntimeHotSnapshot current,
            InterviewSessionRuntimeHotPatch patch) {
        // （1）任一为空时无需校验
        if (current == null || patch == null) {
            return;
        }
        // （2）轮次序号与归档水位不得回退
        if (current.getLastTurnSeq() != null && patch.getLastTurnSeq() != null
                && patch.getLastTurnSeq() < current.getLastTurnSeq()) {
            throw new IllegalStateException("hot snapshot lastTurnSeq regressed");
        }
        if (current.getArchiveWatermark() != null && patch.getArchiveWatermark() != null
                && patch.getArchiveWatermark() < current.getArchiveWatermark()) {
            throw new IllegalStateException("hot snapshot archiveWatermark regressed");
        }
        // （3）计分题数不得回退
        if (current.getScoreAggregate() != null && patch.getScoreAggregate() != null) {
            Integer currentScoreCount = current.getScoreAggregate().getScoreCount();
            Integer patchScoreCount = patch.getScoreAggregate().getScoreCount();
            if (currentScoreCount != null && patchScoreCount != null && patchScoreCount < currentScoreCount) {
                throw new IllegalStateException("hot snapshot scoreCount regressed");
            }
        }
        // （4）未完成流程的当前下标不得回退
        if (current.getFlow() != null && patch.getFlow() != null) {
            Integer currentIndex = current.getFlow().getCurrentIndex();
            Integer patchIndex = patch.getFlow().getCurrentIndex();
            if (!current.getFlow().isCompleted() && currentIndex != null && patchIndex != null && patchIndex < currentIndex) {
                throw new IllegalStateException("hot snapshot flow index regressed");
            }
        }
    }

    private String normalizeMutationId(String requestId) {
        return StrUtil.isBlank(requestId) ? null : requestId.trim();
    }

    /**
     * CAS 失败后的退避等待：基础间隔按重试次数递增，并叠加随机抖动避免同时重试。
     */
    private void backoffHotSnapshotRetry(int attempt) {
        // （1）计算退避时长并随机抖动
        long baseDelay = HOT_SNAPSHOT_CAS_BASE_BACKOFF_MILLIS * (attempt + 1L);
        long jitter = ThreadLocalRandom.current().nextLong(10L, 30L);
        try {
            // （2）休眠等待；被中断时恢复中断标记
            TimeUnit.MILLISECONDS.sleep(baseDelay + jitter);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 归档一轮问答：已存在相同 requestId 的归档时直接复用其序号，否则按最大序号 + 1 追加。
     *
     * @return 本轮归档的序号（代表归档水位线）
     */
    private Long archiveTurn(String sessionId, String requestId, InterviewTurnLog turn, Long snapshotVersion) {
        // （1）会话或轮次为空时返回当前水位线
        if (StrUtil.isBlank(sessionId) || turn == null) {
            return resolveArchiveWatermark(null, sessionId);
        }
        // （2）同一 requestId 已归档时直接返回原序号，保证幂等
        if (StrUtil.isNotBlank(requestId)) {
            Optional<InterviewSessionTurnArchive> existing = turnArchiveRepository.findBySessionIdAndRequestId(sessionId, requestId.trim());
            if (existing.isPresent()) {
                return existing.get().getSeq();
            }
        }
        // （3）取当前最大序号 + 1 作为新序号
        long nextSeq = turnArchiveRepository.findFirstBySessionIdOrderBySeqDesc(sessionId)
                .map(InterviewSessionTurnArchive::getSeq)
                .map(value -> value == null ? 1L : value + 1L)
                .orElse(1L);
        // （4）落库归档记录
        InterviewSessionTurnArchive archive = new InterviewSessionTurnArchive();
        archive.setSessionId(sessionId);
        archive.setRequestId(StrUtil.blankToDefault(requestId, null));
        archive.setSeq(nextSeq);
        archive.setSnapshotVersion(snapshotVersion == null ? 0L : snapshotVersion + 1L);
        archive.setTurnPayload(turn);
        turnArchiveRepository.save(archive);
        return nextSeq;
    }

    /**
     * 在快照中匹配可回放的轮次：先按 requestId 精确匹配，再按题号 + 答案摘要匹配。
     *
     * @return 命中的轮次，未命中时返回 null
     */
    private InterviewTurnLog findReplayTurn(
            InterviewSessionRuntimeSnapshot snapshot,
            String requestId,
            String questionNumber,
            String answerContent) {
        // （1）优先按 requestId 从最近轮次倒序匹配
        List<InterviewTurnLog> recentTurns = snapshot.getRecentTurns();
        if (recentTurns != null && !recentTurns.isEmpty() && StrUtil.isNotBlank(requestId)) {
            for (int index = recentTurns.size() - 1; index >= 0; index--) {
                InterviewTurnLog turn = recentTurns.get(index);
                if (turn != null && StrUtil.equals(requestId.trim(), StrUtil.blankToDefault(turn.getRequestId(), "").trim())) {
                    return turn;
                }
            }
        }
        // （2）题号或答案为空时无法按内容匹配
        if (StrUtil.isBlank(questionNumber) || StrUtil.isBlank(answerContent)) {
            return null;
        }
        // （3）按「题号 + 答案」摘要匹配最近轮次
        String expectedDigest = buildTurnDigest(questionNumber, answerContent);
        if (recentTurns != null && !recentTurns.isEmpty()) {
            for (int index = recentTurns.size() - 1; index >= 0; index--) {
                InterviewTurnLog turn = recentTurns.get(index);
                if (turn != null && StrUtil.equals(expectedDigest, buildTurnDigest(turn.getQuestionNumber(), turn.getAnswerContent()))) {
                    return turn;
                }
            }
        }
        // （4）快照中的最后提交摘要也匹配时，取归档中的最后一条轮次
        if (StrUtil.equals(snapshot.getLastCommittedTurnDigest(), expectedDigest)
                && StrUtil.equals(normalizeQuestionNumber(snapshot.getLastCommittedQuestionNumber()), normalizeQuestionNumber(questionNumber))) {
            List<InterviewSessionTurnArchive> archives = loadTurnArchives(snapshot.getSessionId());
            if (archives != null && !archives.isEmpty()) {
                InterviewSessionTurnArchive lastArchive = archives.get(archives.size() - 1);
                return lastArchive == null ? null : lastArchive.getTurnPayload();
            }
        }
        return null;
    }

    /**
     * 由历史轮次构造答卷响应，用于重复请求的幂等回放。
     */
    private InterviewAnswerRespDTO buildReplayResponse(InterviewTurnLog turn) {
        // （1）轮次为空时返回 null
        if (turn == null) {
            return null;
        }
        // （2）回填当前题目与评分信息
        InterviewAnswerRespDTO response = InterviewAnswerRespDTO.init();
        response.withCurrentQuestion(turn.getQuestionNumber(), turn.getQuestionContent());
        response.withEvaluation(turn.getScore(), turn.getFeedback(), turn.getTotalScore());
        // （3）已结束的轮次直接返回完成态
        if (Boolean.TRUE.equals(turn.getFinished())) {
            response.finish().success();
            return response;
        }
        // （4）否则回填下一题信息（含是否追问与追问序号）
        String nextQuestionNumber = normalizeQuestionNumber(turn.getNextQuestionNumber());
        response.withNextQuestion(
                nextQuestionNumber,
                turn.getNextQuestion(),
                isFollowUpQuestion(nextQuestionNumber),
                resolveFollowUpCount(nextQuestionNumber)
        ).success();
        return response;
    }

    /**
     * 合并冷热快照为统一快照视图，两边均不存在时返回 null。
     */
    private InterviewSessionRuntimeSnapshot assembleSnapshot(
            InterviewSessionRuntimeHotSnapshot hotSnapshot,
            InterviewSessionRuntimeColdSnapshot coldSnapshot) {
        // （1）冷热快照都为空时没有可合并内容
        if (hotSnapshot == null && coldSnapshot == null) {
            return null;
        }
        // （2）以热快照为主、冷快照补全材料字段逐项合并
        InterviewSessionRuntimeSnapshot snapshot = new InterviewSessionRuntimeSnapshot();
        snapshot.setId(hotSnapshot != null ? hotSnapshot.getId() : coldSnapshot.getId());
        snapshot.setSessionId(hotSnapshot != null ? hotSnapshot.getSessionId() : coldSnapshot.getSessionId());
        snapshot.setUserId(hotSnapshot != null ? hotSnapshot.getUserId() : coldSnapshot.getUserId());
        snapshot.setSessionStatus(hotSnapshot == null ? null : hotSnapshot.getSessionStatus());
        snapshot.setSnapshotVersion(hotSnapshot == null ? null : hotSnapshot.getSnapshotVersion());
        snapshot.setSnapshotLevel(hotSnapshot == null ? null : hotSnapshot.getSnapshotLevel());
        snapshot.setRebuildConfidence(hotSnapshot == null ? null : hotSnapshot.getRebuildConfidence());
        snapshot.setSnapshotUpdatedAt(hotSnapshot == null ? null : hotSnapshot.getSnapshotUpdatedAt());
        snapshot.setResumeFileUrl(coldSnapshot == null ? null : coldSnapshot.getResumeFileUrl());
        snapshot.setInterviewType(coldSnapshot == null ? null : coldSnapshot.getInterviewType());
        snapshot.setDirection(coldSnapshot == null ? null : coldSnapshot.getDirection());
        snapshot.setQuestions(coldSnapshot == null || coldSnapshot.getQuestions() == null
                ? null : new LinkedHashMap<>(coldSnapshot.getQuestions()));
        snapshot.setSuggestions(coldSnapshot == null || coldSnapshot.getSuggestions() == null
                ? null : new LinkedHashMap<>(coldSnapshot.getSuggestions()));
        snapshot.setResumeContext(coldSnapshot == null || coldSnapshot.getResumeContext() == null
                ? null : new LinkedHashMap<>(coldSnapshot.getResumeContext()));
        snapshot.setResumeScore(coldSnapshot == null ? null : coldSnapshot.getResumeScore());
        snapshot.setDemeanorScore(coldSnapshot == null ? null : coldSnapshot.getDemeanorScore());
        snapshot.setDemeanorDetails(coldSnapshot == null ? null : coldSnapshot.getDemeanorDetails());
        snapshot.setFlow(hotSnapshot == null ? null : hotSnapshot.getFlow());
        snapshot.setScoreAggregate(hotSnapshot == null ? null : hotSnapshot.getScoreAggregate());
        snapshot.setFollowUpQuestions(hotSnapshot == null || hotSnapshot.getFollowUpQuestions() == null
                ? null : new LinkedHashMap<>(hotSnapshot.getFollowUpQuestions()));
        snapshot.setRecentTurns(hotSnapshot == null || hotSnapshot.getRecentTurns() == null
                ? null : new ArrayList<>(hotSnapshot.getRecentTurns()));
        snapshot.setRecentTurnCount(hotSnapshot == null ? null : hotSnapshot.getRecentTurnCount());
        snapshot.setArchiveWatermark(hotSnapshot == null ? null : hotSnapshot.getArchiveWatermark());
        snapshot.setLastTurnSeq(hotSnapshot == null ? null : hotSnapshot.getLastTurnSeq());
        snapshot.setLastAppliedRequestId(hotSnapshot == null ? null : hotSnapshot.getLastAppliedRequestId());
        snapshot.setLastMutationId(hotSnapshot == null ? null : hotSnapshot.getLastMutationId());
        snapshot.setLastMutationTime(hotSnapshot == null ? null : hotSnapshot.getLastMutationTime());
        snapshot.setLastCommittedQuestionNumber(hotSnapshot == null ? null : hotSnapshot.getLastCommittedQuestionNumber());
        snapshot.setLastCommittedTurnDigest(hotSnapshot == null ? null : hotSnapshot.getLastCommittedTurnDigest());
        snapshot.setMaterialVersion(coldSnapshot == null ? null : coldSnapshot.getMaterialVersion());
        snapshot.setCreateTime(hotSnapshot != null ? hotSnapshot.getCreateTime() : coldSnapshot.getCreateTime());
        snapshot.setUpdateTime(hotSnapshot != null ? hotSnapshot.getUpdateTime() : coldSnapshot.getUpdateTime());
        // （3）返回合并后的快照
        return snapshot;
    }

    /**
     * 解析面试类型：按题目、冷快照、会话的优先级取第一个非空值。
     */
    private String resolveInterviewType(
            InterviewSession session,
            InterviewQuestion question,
            InterviewSessionRuntimeColdSnapshot coldSnapshot) {
        // （1）按优先级依次尝试各来源的类型字段
        if (question != null && StrUtil.isNotBlank(question.getInterviewType())) {
            return question.getInterviewType().trim();
        }
        if (coldSnapshot != null && StrUtil.isNotBlank(coldSnapshot.getInterviewType())) {
            return coldSnapshot.getInterviewType().trim();
        }
        return session == null ? null : session.getInterviewType();
    }

    /**
     * 解析题目映射：优先取缓存，缺失时从题目记录的 JSON 或列表解析。
     */
    private Map<String, String> resolveQuestions(String sessionId, InterviewQuestion question) {
        // （1）缓存中有题目时直接返回副本
        Map<String, String> questions = interviewQuestionCacheService.getSessionInterviewQuestions(sessionId);
        if (questions != null && !questions.isEmpty()) {
            return new LinkedHashMap<>(questions);
        }
        // （2）缓存缺失时从题目记录解析
        return parseStringMap(question == null ? null : question.getQuestionsJson(), question == null ? null : question.getQuestions());
    }

    /**
     * 解析面试建议映射：优先取缓存，缺失时从题目记录的 JSON 或列表解析。
     */
    private Map<String, String> resolveSuggestions(String sessionId, InterviewQuestion question) {
        // （1）缓存中有建议时直接返回副本
        Map<String, String> suggestions = interviewQuestionCacheService.getSessionInterviewSuggestions(sessionId);
        if (suggestions != null && !suggestions.isEmpty()) {
            return new LinkedHashMap<>(suggestions);
        }
        // （2）缓存缺失时从题目记录解析
        return parseStringMap(question == null ? null : question.getSuggestionsJson(), question == null ? null : question.getSuggestions());
    }

    /**
     * 解析简历上下文：优先取缓存，缺失时解析题目保存的原始响应 JSON。
     */
    private Map<String, Object> resolveResumeContext(String sessionId, InterviewQuestion question) {
        // （1）缓存中有上下文时直接返回副本
        Map<String, Object> resumeContext = interviewQuestionCacheService.getSessionResumeContext(sessionId);
        if (resumeContext != null && !resumeContext.isEmpty()) {
            return new LinkedHashMap<>(resumeContext);
        }
        // （2）题目或原始响应为空时返回空 Map
        if (question == null || StrUtil.isBlank(question.getRawResponseData())) {
            return new LinkedHashMap<>();
        }
        // （3）解析原始响应 JSON，异常时降级为空 Map
        try {
            Map<String, Object> parsed = JSON.parseObject(question.getRawResponseData(), new TypeReference<LinkedHashMap<String, Object>>() {
            });
            return parsed == null ? new LinkedHashMap<>() : parsed;
        } catch (Exception ex) {
            return new LinkedHashMap<>();
        }
    }

    /**
     * 解析简历评分：优先取缓存，缺失时回落到题目记录的分数。
     */
    private Integer resolveResumeScore(String sessionId, InterviewQuestion question) {
        // （1）缓存中有分数时直接返回
        Integer resumeScore = interviewQuestionCacheService.getSessionResumeScore(sessionId);
        if (resumeScore != null) {
            return resumeScore;
        }
        // （2）缓存缺失时使用题目记录中的分数
        return question == null ? null : question.getResumeScore();
    }

    /**
     * 解析面试方向：按缓存、题目、会话的优先级取第一个非空值。
     */
    private String resolveDirection(String sessionId, InterviewSession session, InterviewQuestion question) {
        // （1）按优先级依次尝试各来源的方向字段
        String direction = interviewQuestionCacheService.getSessionInterviewDirection(sessionId);
        if (StrUtil.isNotBlank(direction)) {
            return direction;
        }
        if (question != null && StrUtil.isNotBlank(question.getInterviewType())) {
            return question.getInterviewType().trim();
        }
        return session == null ? null : session.getInterviewType();
    }

    /**
     * 解析轮次列表：按缓存、归档、快照的顺序取数据源，并追加本轮待提交轮次。
     */
    private List<InterviewTurnLog> resolveTurns(
            String sessionId,
            InterviewSessionRuntimeSnapshot snapshot,
            InterviewTurnLog committedTurn) {
        // （1）优先使用缓存中的轮次
        List<InterviewTurnLog> turns = interviewQuestionCacheService.getInterviewTurns(sessionId);
        if (turns != null && !turns.isEmpty()) {
            List<InterviewTurnLog> merged = new ArrayList<>(turns);
            appendCommittedTurnIfAbsent(merged, committedTurn);
            return merged;
        }
        // （2）其次使用轮次归档
        List<InterviewSessionTurnArchive> archives = loadTurnArchives(sessionId);
        if (archives != null && !archives.isEmpty()) {
            List<InterviewTurnLog> merged = archives.stream()
                    .map(InterviewSessionTurnArchive::getTurnPayload)
                    .filter(item -> item != null)
                    .collect(Collectors.toCollection(ArrayList::new));
            appendCommittedTurnIfAbsent(merged, committedTurn);
            return merged;
        }
        // （3）再次使用快照中的最近轮次
        if (snapshot != null && snapshot.getRecentTurns() != null && !snapshot.getRecentTurns().isEmpty()) {
            List<InterviewTurnLog> merged = new ArrayList<>(snapshot.getRecentTurns());
            appendCommittedTurnIfAbsent(merged, committedTurn);
            return merged;
        }
        // （4）都没有时仅返回本轮待提交轮次
        if (committedTurn == null) {
            return new ArrayList<>();
        }
        return new ArrayList<>(List.of(committedTurn));
    }

    /**
     * 把本轮待提交轮次追加到列表末尾，同 requestId 已存在时不重复追加。
     */
    private void appendCommittedTurnIfAbsent(List<InterviewTurnLog> turns, InterviewTurnLog committedTurn) {
        // （1）参数为空时忽略
        if (turns == null || committedTurn == null) {
            return;
        }
        // （2）无 requestId 时无法判重，直接追加
        String requestId = committedTurn.getRequestId();
        if (StrUtil.isBlank(requestId)) {
            turns.add(committedTurn);
            return;
        }
        // （3）存在相同 requestId 时跳过，避免重复计分
        for (InterviewTurnLog turn : turns) {
            if (turn != null && StrUtil.equals(requestId.trim(), StrUtil.blankToDefault(turn.getRequestId(), "").trim())) {
                return;
            }
        }
        turns.add(committedTurn);
    }

    /**
     * 截取最近 N 条轮次，控制热快照的存储体积。
     */
    private List<InterviewTurnLog> limitRecentTurns(List<InterviewTurnLog> turns) {
        // （1）轮次为空时返回空列表
        if (turns == null || turns.isEmpty()) {
            return new ArrayList<>();
        }
        // （2）从尾部截取最近 RECENT_TURN_LIMIT 条
        int size = turns.size();
        int start = Math.max(0, size - RECENT_TURN_LIMIT);
        return new ArrayList<>(turns.subList(start, size));
    }

    /**
     * 解析流程状态：优先取缓存并复制，缺失时按会话终态、轮次与题目数推导。
     */
    private InterviewFlowState resolveFlow(
            String sessionId,
            InterviewSession session,
            Map<String, String> questions,
            List<InterviewTurnLog> turns) {
        // （1）缓存中有流程时返回副本，避免被外部修改
        InterviewFlowState flow = interviewQuestionCacheService.getInterviewFlow(sessionId);
        if (flow != null) {
            return cloneFlow(flow);
        }
        // （2）会话已结束时直接构造完成态
        int totalQuestions = questions == null ? 0 : questions.size();
        if (isTerminalSession(session)) {
            return buildCompletedFlow(totalQuestions);
        }
        // （3）有轮次时按轮次推导，否则按初始状态处理
        if (turns != null && !turns.isEmpty()) {
            return deriveFlowFromTurns(turns, totalQuestions);
        }
        return totalQuestions > 0 ? buildInitialFlow(totalQuestions) : null;
    }

    /**
     * 统计分数汇总：累加非追问轮次的得分，总分取缓存中的会话总分。
     */
    private InterviewRuntimeScoreAggregate resolveScoreAggregate(String sessionId, List<InterviewTurnLog> turns) {
        // （1）遍历轮次累加分数总和与计分题数（追问不计入）
        InterviewRuntimeScoreAggregate aggregate = emptyScoreAggregate();
        if (turns != null) {
            for (InterviewTurnLog turn : turns) {
                if (turn == null || turn.getScore() == null || Boolean.TRUE.equals(turn.getIsFollowUp())) {
                    continue;
                }
                aggregate.setScoreSum((aggregate.getScoreSum() == null ? 0 : aggregate.getScoreSum()) + clampScore(turn.getScore()));
                aggregate.setScoreCount((aggregate.getScoreCount() == null ? 0 : aggregate.getScoreCount()) + 1);
            }
        }
        // （2）总分取缓存，缺失时记为 0
        Integer totalScore = interviewQuestionCacheService.getSessionTotalScore(sessionId);
        aggregate.setTotalScore(totalScore == null ? 0 : totalScore);
        return aggregate;
    }

    /**
     * 解析追问题目：优先取缓存，缺失时从轮次的下一题字段推导。
     */
    private Map<String, String> resolveFollowUpQuestions(String sessionId, List<InterviewTurnLog> turns) {
        // （1）缓存中有追问时直接返回副本
        Map<String, String> followUpQuestions = interviewQuestionCacheService.getSessionFollowUpQuestions(sessionId);
        if (followUpQuestions != null && !followUpQuestions.isEmpty()) {
            return new LinkedHashMap<>(followUpQuestions);
        }
        // （2）从轮次中提取追问题号与题干
        Map<String, String> derived = new LinkedHashMap<>();
        if (turns == null) {
            return derived;
        }
        for (InterviewTurnLog turn : turns) {
            if (turn == null || StrUtil.isBlank(turn.getNextQuestionNumber()) || StrUtil.isBlank(turn.getNextQuestion())) {
                continue;
            }
            String nextQuestionNumber = normalizeQuestionNumber(turn.getNextQuestionNumber());
            if (isFollowUpQuestion(nextQuestionNumber)) {
                derived.put(nextQuestionNumber, turn.getNextQuestion().trim());
            }
        }
        return derived;
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
     * 评估快照可重建的置信度：终态最高，题目与流程齐全为 EXACT，仅有题目为 DERIVED。
     */
    private InterviewRuntimeConfidence resolveConfidence(
            InterviewSession session,
            Map<String, String> questions,
            InterviewFlowState flow) {
        // （1）终态会话置信度为 TERMINAL
        if (isTerminalSession(session)) {
            return InterviewRuntimeConfidence.TERMINAL;
        }
        // （2）题目与流程齐全为 EXACT，仅有题目为 DERIVED，其余只读
        if (questions != null && !questions.isEmpty() && flow != null) {
            return InterviewRuntimeConfidence.EXACT;
        }
        if (questions != null && !questions.isEmpty()) {
            return InterviewRuntimeConfidence.DERIVED;
        }
        return InterviewRuntimeConfidence.READ_ONLY;
    }

    /**
     * 构造全零的分数汇总对象。
     */
    private InterviewRuntimeScoreAggregate emptyScoreAggregate() {
        // （1）初始化分数总和、计分题数与总分为 0
        InterviewRuntimeScoreAggregate aggregate = new InterviewRuntimeScoreAggregate();
        aggregate.setScoreSum(0);
        aggregate.setScoreCount(0);
        aggregate.setTotalScore(0);
        return aggregate;
    }

    /**
     * 解析归档水位线：已有有效值直接沿用，否则取归档表中的最大序号。
     */
    private Long resolveArchiveWatermark(Long existingWatermark, String sessionId) {
        // （1）已有有效水位线时直接返回
        if (existingWatermark != null && existingWatermark > 0) {
            return existingWatermark;
        }
        // （2）否则取归档表最大序号，无归档时为 0
        return turnArchiveRepository.findFirstBySessionIdOrderBySeqDesc(sessionId)
                .map(InterviewSessionTurnArchive::getSeq)
                .orElse(0L);
    }

    private Long nextVersion(Long currentVersion) {
        return currentVersion == null ? 1L : currentVersion + 1L;
    }

    private int clampScore(Integer score) {
        if (score == null) {
            return 0;
        }
        return Math.max(0, Math.min(score, 100));
    }

    /**
     * 由轮次推导流程状态：按最新轮次判断是否已结束，或推进到其记录的下一题。
     */
    private InterviewFlowState deriveFlowFromTurns(List<InterviewTurnLog> turns, int totalQuestions) {
        // （1）无轮次时按初始状态或空流程处理
        if (turns == null || turns.isEmpty()) {
            return totalQuestions > 0 ? buildInitialFlow(totalQuestions) : null;
        }
        // （2）取最新轮次，为空时按初始状态处理
        InterviewTurnLog latestTurn = turns.get(turns.size() - 1);
        if (latestTurn == null) {
            return totalQuestions > 0 ? buildInitialFlow(totalQuestions) : null;
        }
        // （3）最新轮次已结束则流程完成
        if (Boolean.TRUE.equals(latestTurn.getFinished())) {
            return buildCompletedFlow(totalQuestions);
        }
        // （4）有下一题时直接按下一题构造流程
        String nextQuestionNumber = normalizeQuestionNumber(latestTurn.getNextQuestionNumber());
        if (StrUtil.isNotBlank(nextQuestionNumber)) {
            return buildFlowForQuestion(nextQuestionNumber, totalQuestions);
        }
        // （5）否则按已答主问题序号 + 1 推导，超出总题数视为完成
        Integer answeredMainNo = extractMainQuestionNo(latestTurn.getQuestionNumber());
        if (answeredMainNo == null) {
            return totalQuestions > 0 ? buildInitialFlow(totalQuestions) : null;
        }
        if (totalQuestions > 0 && answeredMainNo >= totalQuestions) {
            return buildCompletedFlow(totalQuestions);
        }
        return buildFlowForQuestion(String.valueOf(answeredMainNo + 1), totalQuestions);
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
    private InterviewFlowState buildFlowForQuestion(String questionNumber, int totalQuestions) {
        // （1）解析主问题序号并判断状态是提问还是追问
        InterviewFlowState flow = new InterviewFlowState();
        Integer mainQuestionNo = extractMainQuestionNo(questionNumber);
        flow.setStatus(isFollowUpQuestion(questionNumber) ? "FOLLOW_UP" : "ASKING");
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
     * 复制流程状态，避免缓存对象被外部直接修改。
     */
    private InterviewFlowState cloneFlow(InterviewFlowState flow) {
        // （1）流程为空时返回 null
        if (flow == null) {
            return null;
        }
        // （2）逐字段复制为新对象
        InterviewFlowState cloned = new InterviewFlowState();
        cloned.setStatus(flow.getStatus());
        cloned.setCurrentIndex(flow.getCurrentIndex());
        cloned.setCurrentQuestionNumber(flow.getCurrentQuestionNumber());
        cloned.setTotalQuestions(flow.getTotalQuestions());
        cloned.setFollowUpCount(flow.getFollowUpCount());
        cloned.setMaxFollowUp(flow.getMaxFollowUp());
        cloned.setVersion(flow.getVersion());
        return cloned;
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

    /** 计算「题号 + 答案」的摘要，用于按内容识别重复提交。 */
    private String buildTurnDigest(String questionNumber, String answerContent) {
        return DigestUtil.sha256Hex(normalizeQuestionNumber(questionNumber) + "|" + truncateAnswer(answerContent));
    }

    /** 截断答案到前 1000 个字符，控制摘要计算开销。 */
    private String truncateAnswer(String answerContent) {
        if (answerContent == null) {
            return "";
        }
        return answerContent.length() <= 1000 ? answerContent : answerContent.substring(0, 1000);
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
        if (!isFollowUpQuestion(questionNumber)) {
            return 0;
        }
        // （2）截取 "-F" 之后的数字作为追问序号
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

    /** 判断题号是否符合「主问题-F追问序号」格式。 */
    private boolean isFollowUpQuestion(String questionNumber) {
        return StrUtil.isNotBlank(questionNumber) && questionNumber.trim().toUpperCase().matches("\\d+-F\\d+");
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
}
