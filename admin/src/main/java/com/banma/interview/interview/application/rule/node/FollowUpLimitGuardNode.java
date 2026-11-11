package com.banma.interview.interview.application.rule.node;

import com.banma.interview.interview.application.rule.InterviewFollowUpRuleContext;
import com.yomahub.liteflow.annotation.LiteflowComponent;
import com.yomahub.liteflow.core.NodeComponent;

/**
 * LiteFlow 节点：追问次数达到上限时禁止继续追问。
 */
@LiteflowComponent("followUpLimitGuard")
public class FollowUpLimitGuardNode extends NodeComponent {

    /**
     * 追问次数已达到解析出的上限时，标记无需追问。
     */
    @Override
    public void process() {
        // （1）获取当前会话的规则上下文
        InterviewFollowUpRuleContext context = getContextBean(InterviewFollowUpRuleContext.class);
        // （2）已终止的流程不再继续判定
        if (context.isTerminated()) {
            return;
        }
        // （3）追问次数达到上限则标记无需追问
        if (context.getFollowUpCount() >= context.getResolvedMaxFollowUp()) {
            context.markNoFollowUp("FOLLOW_UP_LIMIT_REACHED", "follow-up limit reached");
        }
    }
}
