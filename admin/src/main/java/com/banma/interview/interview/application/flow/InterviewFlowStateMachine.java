package com.banma.interview.interview.application.flow;

import cn.hutool.core.util.StrUtil;
import com.banma.interview.interview.service.InterviewQuestionCacheService;
import com.banma.interview.interview.service.model.InterviewFlowState;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 面试答题流程状态机，负责校验状态流转合法性并提供题目推进能力。
 */
@Component
@RequiredArgsConstructor
public class InterviewFlowStateMachine {

    private static final Map<InterviewFlowStatus, Set<InterviewFlowStatus>> LEGAL_TRANSITIONS = new EnumMap<>(InterviewFlowStatus.class);

    // 初始化合法流转表：COMPLETED 为终态，不再允许向外迁移
    static {
        LEGAL_TRANSITIONS.put(InterviewFlowStatus.INIT, EnumSet.of(InterviewFlowStatus.ASKING, InterviewFlowStatus.COMPLETED));
        LEGAL_TRANSITIONS.put(InterviewFlowStatus.ASKING, EnumSet.of(
                InterviewFlowStatus.EVALUATING,
                InterviewFlowStatus.FOLLOW_UP,
                InterviewFlowStatus.COMPLETED
        ));
        LEGAL_TRANSITIONS.put(InterviewFlowStatus.EVALUATING, EnumSet.of(
                InterviewFlowStatus.ASKING,
                InterviewFlowStatus.FOLLOW_UP,
                InterviewFlowStatus.COMPLETED
        ));
        LEGAL_TRANSITIONS.put(InterviewFlowStatus.FOLLOW_UP, EnumSet.of(
                InterviewFlowStatus.EVALUATING,
                InterviewFlowStatus.ASKING,
                InterviewFlowStatus.COMPLETED
        ));
        LEGAL_TRANSITIONS.put(InterviewFlowStatus.COMPLETED, EnumSet.noneOf(InterviewFlowStatus.class));
    }

    /** 题目、流程与得分的 Redis 缓存读写 */
    private final InterviewQuestionCacheService interviewQuestionCacheService;

    /**
     * 确保会话已初始化流程状态：缺失时按题目总数初始化并回读最新状态。
     *
     * @param sessionId 面试会话 id
     * @param totalQuestions 本次面试的题目总数
     * @return 初始化后的流程状态，参数非法时返回 null
     */
    public InterviewFlowState ensureInitialized(String sessionId, int totalQuestions) {
        // （1）会话 id 或题目总数非法时直接返回
        if (StrUtil.isBlank(sessionId) || totalQuestions <= 0) {
            return null;
        }
        // （2）已存在流程状态则直接复用
        InterviewFlowState state = interviewQuestionCacheService.getInterviewFlow(sessionId);
        if (state != null) {
            return state;
        }
        // （3）初始化流程并回读一次最新状态
        interviewQuestionCacheService.initInterviewFlow(sessionId, totalQuestions);
        return interviewQuestionCacheService.getInterviewFlow(sessionId);
    }

    /**
     * 查询会话当前的流程状态。
     */
    public InterviewFlowState current(String sessionId) {
        // （1）会话 id 为空时返回 null，避免无意义的缓存读取
        if (StrUtil.isBlank(sessionId)) {
            return null;
        }
        return interviewQuestionCacheService.getInterviewFlow(sessionId);
    }

    /** 判断流程是否已进入完成态。 */
    public boolean isCompleted(InterviewFlowState state) {
        return toStatus(state) == InterviewFlowStatus.COMPLETED;
    }

    /**
     * 判断当前题目下标是否已超出题目总数（即所有主问题均已回答）。
     */
    public boolean isOutOfRange(InterviewFlowState state) {
        // （1）状态为空时视为未越界
        if (state == null) {
            return false;
        }
        // （2）比较题目总数与当前下标
        Integer totalQuestions = state.getTotalQuestions();
        Integer currentIndex = state.getCurrentIndex();
        return totalQuestions != null && totalQuestions > 0
                && currentIndex != null && currentIndex >= totalQuestions;
    }

    /**
     * 取当前题号：优先使用状态中的题号，缺失时以下标 + 1 兜底。
     *
     * @return 题号字符串，状态为空或已越界时返回 null
     */
    public String currentQuestionNumber(InterviewFlowState state) {
        // （1）状态缺失或已超出题目范围时无当前题号
        if (state == null || isOutOfRange(state)) {
            return null;
        }
        // （2）状态中已有显式题号时直接返回
        if (StrUtil.isNotBlank(state.getCurrentQuestionNumber())) {
            return state.getCurrentQuestionNumber().trim();
        }
        // （3）否则以「当前下标 + 1」推导题号
        int currentIndex = state.getCurrentIndex() == null ? 0 : Math.max(0, state.getCurrentIndex());
        return String.valueOf(currentIndex + 1);
    }

    /** 将流程迁移到「评估中」状态。 */
    public InterviewFlowState moveToEvaluating(String sessionId) {
        return transitionStatus(sessionId, InterviewFlowStatus.EVALUATING);
    }

    /** 将流程迁移到「追问」状态。 */
    public InterviewFlowState moveToFollowUp(String sessionId) {
        return transitionStatus(sessionId, InterviewFlowStatus.FOLLOW_UP);
    }

    /**
     * 开始一道追问题目，校验当前状态是否允许进入追问。
     *
     * @param sessionId 面试会话 id
     * @param questionNumber 追问题号
     * @return 更新后的流程状态，状态缺失时返回 null
     * @throws IllegalStateException 当前状态不允许流转到追问时抛出
     */
    public InterviewFlowState startFollowUpQuestion(String sessionId, String questionNumber) {
        // （1）读取当前流程状态
        InterviewFlowState currentState = current(sessionId);
        if (currentState == null) {
            return null;
        }
        // （2）校验状态流转是否合法（已处于追问态时允许继续追问）
        InterviewFlowStatus currentStatus = toStatus(currentState);
        if (!isLegalTransition(currentStatus, InterviewFlowStatus.FOLLOW_UP)
                && currentStatus != InterviewFlowStatus.FOLLOW_UP) {
            throw illegalTransition(currentStatus, InterviewFlowStatus.FOLLOW_UP);
        }
        // （3）写入追问题目并返回最新状态
        return interviewQuestionCacheService.startFollowUpQuestion(sessionId, questionNumber);
    }

    /**
     * 将流程标记为完成，已是完成态时幂等返回当前状态。
     *
     * @throws IllegalStateException 当前状态不允许流转到完成态时抛出
     */
    public InterviewFlowState markCompleted(String sessionId) {
        // （1）读取当前状态，缺失或已完成时直接返回
        InterviewFlowState currentState = current(sessionId);
        if (currentState == null || isCompleted(currentState)) {
            return currentState;
        }
        // （2）校验是否可以流转到完成态
        if (!isLegalTransition(toStatus(currentState), InterviewFlowStatus.COMPLETED)) {
            throw illegalTransition(toStatus(currentState), InterviewFlowStatus.COMPLETED);
        }
        // （3）执行完成标记
        return interviewQuestionCacheService.markInterviewCompleted(sessionId);
    }

    /**
     * 推进到下一道主问题，越界时自动把流程标记为完成。
     *
     * @throws IllegalStateException 当前状态不允许继续提问时抛出
     */
    public InterviewFlowState advanceMainQuestion(String sessionId) {
        // （1）读取当前流程状态
        InterviewFlowState currentState = current(sessionId);
        if (currentState == null) {
            return null;
        }
        // （2）仅允许在提问、评估、追问阶段推进
        InterviewFlowStatus currentStatus = toStatus(currentState);
        if (currentStatus != InterviewFlowStatus.EVALUATING
                && currentStatus != InterviewFlowStatus.FOLLOW_UP
                && currentStatus != InterviewFlowStatus.ASKING) {
            throw illegalTransition(currentStatus, InterviewFlowStatus.ASKING);
        }

        // （3）推进题目标
        InterviewFlowState next = interviewQuestionCacheService.advanceToNextQuestion(sessionId);
        if (next == null) {
            return null;
        }
        // （4）题目已全部问完时收尾为完成态
        if (isOutOfRange(next)) {
            return markCompleted(sessionId);
        }
        return next;
    }

    /**
     * 通用的状态流转实现：校验合法性后写回缓存并回读最新状态。
     */
    private InterviewFlowState transitionStatus(String sessionId, InterviewFlowStatus targetStatus) {
        // （1）读取当前状态，缺失或目标状态为空时原样返回
        InterviewFlowState currentState = current(sessionId);
        if (currentState == null || targetStatus == null) {
            return currentState;
        }

        // （2）状态未变化时直接返回，避免无谓写缓存
        InterviewFlowStatus sourceStatus = toStatus(currentState);
        if (sourceStatus == targetStatus) {
            return currentState;
        }
        // （3）校验流转是否合法
        if (!isLegalTransition(sourceStatus, targetStatus)) {
            throw illegalTransition(sourceStatus, targetStatus);
        }
        // （4）写回新状态并回读
        interviewQuestionCacheService.updateInterviewFlowStatus(sessionId, targetStatus.name());
        return current(sessionId);
    }

    /** 判断两个状态之间的迁移是否在合法流转表中。 */
    private boolean isLegalTransition(InterviewFlowStatus sourceStatus, InterviewFlowStatus targetStatus) {
        if (sourceStatus == null || targetStatus == null) {
            return false;
        }
        Set<InterviewFlowStatus> allowed = LEGAL_TRANSITIONS.get(sourceStatus);
        return allowed != null && allowed.contains(targetStatus);
    }

    private InterviewFlowStatus toStatus(InterviewFlowState state) {
        return InterviewFlowStatus.from(state == null ? null : state.getStatus());
    }

    private IllegalStateException illegalTransition(InterviewFlowStatus sourceStatus, InterviewFlowStatus targetStatus) {
        return new IllegalStateException("illegal interview flow transition: " + sourceStatus + " -> " + targetStatus);
    }
}
