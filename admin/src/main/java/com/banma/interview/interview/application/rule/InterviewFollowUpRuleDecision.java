package com.banma.interview.interview.application.rule;

import lombok.Data;

/**
 * 后续规则决策输出
 */
@Data
public class InterviewFollowUpRuleDecision {

    private boolean needFollowUp;       // 是否需要追问

    private int resolvedMaxFollowUp;    //已解决的最大追问次数

    private String reasonCode;          // 追问原因编码

    private String reasonText;          // 追问原因文本

    private String chainId;             // 面试链ID

    private String ruleVersion;         // 规则版本

    private boolean fallback;            // 是否为回退规则

    public static InterviewFollowUpRuleDecision noFollowUp(
            int resolvedMaxFollowUp,
            String reasonCode,
            String reasonText,
            String chainId,
            String ruleVersion,
            boolean fallback) {
        InterviewFollowUpRuleDecision decision = new InterviewFollowUpRuleDecision();
        decision.setNeedFollowUp(false);
        decision.setResolvedMaxFollowUp(resolvedMaxFollowUp);
        decision.setReasonCode(reasonCode);
        decision.setReasonText(reasonText);
        decision.setChainId(chainId);
        decision.setRuleVersion(ruleVersion);
        decision.setFallback(fallback);
        return decision;
    }
}
