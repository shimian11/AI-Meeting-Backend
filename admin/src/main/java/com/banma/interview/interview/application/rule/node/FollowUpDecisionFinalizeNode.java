package com.banma.interview.interview.application.rule.node;

import com.banma.interview.interview.application.rule.InterviewFollowUpRuleContext;
import com.yomahub.liteflow.annotation.LiteflowComponent;
import com.yomahub.liteflow.core.NodeComponent;

/**
 * LiteFlow 节点：固化最终追问决策。
 */
@LiteflowComponent("followUpDecisionFinalize")
public class FollowUpDecisionFinalizeNode extends NodeComponent {

    /**
     * 将规则链中累积的判定结果固化为最终决策。
     */
    @Override
    public void process() {
        // （1）获取规则上下文并固化最终决策
        InterviewFollowUpRuleContext context = getContextBean(InterviewFollowUpRuleContext.class);
        context.finalizeDecision(false);
    }
}
