package com.banma.interview.interview.application.rule;

import lombok.Data;

import java.util.List;

/**
 * LiteFlow节点使用的后续规则上下文
 */
@Data
public class InterviewFollowUpRuleContext {

    /** 会话ID */
    private String sessionId;

    /** 本次答题请求ID */
    private String requestId;

    /** 当前题号（如 "Q3" 或 "3-F1"） */
    private String questionNumber;

    /** 面试类型 */
    private String interviewType;

    /** 当前是否为追问题 */
    private boolean followUpQuestion;

    /** 当前追问次数 */
    private int followUpCount;

    /** 最大追问次数（默认值） */
    private int maxFollowUp;

    /** 经规则决策后的实际最大追问次数 */
    private int resolvedMaxFollowUp;

    /** 当前答题得分 */
    private Integer score;

    /** 低分追问阈值（低于此分即触发追问） */
    private int lowScoreThreshold;

    /** AI评估结果中是否建议追问 */
    private boolean followUpNeededFromAi;

    /** AI评估出的缺失知识点列表 */
    private List<String> missingPoints;

    /** AI建议的追问问题提示 */
    private String followUpQuestionHint;

    /** 面试是否已完成 */
    private boolean interviewCompleted;

    /** 当前执行的规则链ID */
    private String chainId;

    /** 规则引擎版本号 */
    private String ruleVersion;

    /** 是否已终止（守卫节点已拦截，跳过后续所有节点） */
    private boolean terminated;

    private InterviewFollowUpRuleDecision decision;

    public void markNoFollowUp(String reasonCode, String reasonText) {
        ensureDecision();
        decision.setNeedFollowUp(false);
        decision.setReasonCode(reasonCode);
        decision.setReasonText(reasonText);
        terminated = true;
    }

    public void markNeedFollowUp(String reasonCode, String reasonText) {
        ensureDecision();
        if (!decision.isNeedFollowUp()) {
            decision.setNeedFollowUp(true);
            decision.setReasonCode(reasonCode);
            decision.setReasonText(reasonText);
        }
    }

    public void finalizeDecision(boolean fallback) {
        ensureDecision();
        if (decision.getReasonCode() == null || decision.getReasonCode().isBlank()) {
            decision.setReasonCode("NO_RULE_TRIGGER");
            decision.setReasonText("no follow-up trigger matched");
            decision.setNeedFollowUp(false);
        }
        decision.setResolvedMaxFollowUp(resolvedMaxFollowUp > 0 ? resolvedMaxFollowUp : Math.max(maxFollowUp, 1));
        decision.setChainId(chainId);
        decision.setRuleVersion(ruleVersion);
        decision.setFallback(fallback);
    }

    private void ensureDecision() {
        if (decision == null) {
            decision = new InterviewFollowUpRuleDecision();
        }
    }
}