package com.banma.interview.interview.application.rule.node;

import com.banma.interview.interview.application.rule.InterviewFollowUpRuleContext;
import com.yomahub.liteflow.annotation.LiteflowComponent;
import com.yomahub.liteflow.core.NodeComponent;

/**
 * LiteFlow 节点：加载并校正追问上限等上下文信息。
 */
@LiteflowComponent("loadFollowUpContext")
public class LoadFollowUpContextNode extends NodeComponent {

    /**
     * 校正追问上限：优先使用已解析值，否则取配置值并保证至少为 1。
     */
    @Override
    public void process() {
        // （1）获取当前会话的规则上下文
        InterviewFollowUpRuleContext context = getContextBean(InterviewFollowUpRuleContext.class);
        // （2）校正追问上限，保证至少为 1
        int resolvedMax = context.getResolvedMaxFollowUp() > 0 ? context.getResolvedMaxFollowUp() : Math.max(context.getMaxFollowUp(), 1);
        context.setResolvedMaxFollowUp(resolvedMax);
    }
}
