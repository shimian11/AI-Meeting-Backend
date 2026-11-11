package com.banma.interview.interview.application.rule.node;

import com.banma.interview.interview.application.rule.InterviewFollowUpRuleContext;
import com.yomahub.liteflow.annotation.LiteflowComponent;
import com.yomahub.liteflow.core.NodeComponent;

/**
 * LiteFlow 节点：依据 AI 评估结果标记是否需要追问。
 */
@LiteflowComponent("aiSuggestionJudge")
public class AiSuggestionJudgeNode extends NodeComponent {

    /**
     * 若上下文未终止且 AI 建议追问，则标记需要追问。
     */
    @Override
    public void process() {
        // （1）获取当前会话的规则上下文
        InterviewFollowUpRuleContext context = getContextBean(InterviewFollowUpRuleContext.class);
        // （2）已终止的流程不再继续判定
        if (context.isTerminated()) {
            return;
        }
        // （3）AI 判定需要追问时记录原因与描述
        if (context.isFollowUpNeededFromAi()) {
            context.markNeedFollowUp("AI_SUGGESTED", "follow-up suggested by ai result");
        }
    }
}
