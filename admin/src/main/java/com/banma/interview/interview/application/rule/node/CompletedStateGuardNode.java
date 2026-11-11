package com.banma.interview.interview.application.rule.node;

import com.banma.interview.interview.application.rule.InterviewFollowUpRuleContext;
import com.yomahub.liteflow.annotation.LiteflowComponent;
import com.yomahub.liteflow.core.NodeComponent;

/**
 * LiteFlow 节点：面试已完成时禁止继续追问。
 */
@LiteflowComponent("completedStateGuard")
public class CompletedStateGuardNode extends NodeComponent {

    /**
     * 面试已处于完成状态时，标记无需追问并说明原因。
     */
    @Override
    public void process() {
        // （1）获取当前会话的规则上下文
        InterviewFollowUpRuleContext context = getContextBean(InterviewFollowUpRuleContext.class);
        // （2）面试已完成则直接标记无需追问
        if (context.isInterviewCompleted()) {
            context.markNoFollowUp("INTERVIEW_COMPLETED", "interview already completed");
        }
    }
}
