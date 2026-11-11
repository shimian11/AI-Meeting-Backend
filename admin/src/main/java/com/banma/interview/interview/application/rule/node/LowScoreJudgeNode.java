package com.banma.interview.interview.application.rule.node;

import com.banma.interview.interview.application.rule.InterviewFollowUpRuleContext;
import com.yomahub.liteflow.annotation.LiteflowComponent;
import com.yomahub.liteflow.core.NodeComponent;

/**
 * LiteFlow 节点：回答评分低于阈值时标记需要追问。
 */
@LiteflowComponent("lowScoreJudge")
public class LowScoreJudgeNode extends NodeComponent {

    /**
     * 评分低于配置的低分阈值时，标记需要追问并附带比较信息。
     */
    @Override
    public void process() {
        // （1）获取当前会话的规则上下文
        InterviewFollowUpRuleContext context = getContextBean(InterviewFollowUpRuleContext.class);
        // （2）已终止的流程不再继续判定
        if (context.isTerminated()) {
            return;
        }
        // （3）评分存在且低于阈值时标记需要追问
        Integer score = context.getScore();
        if (score != null && score < context.getLowScoreThreshold()) {
            context.markNeedFollowUp(
                    "LOW_SCORE",
                    "score below threshold: " + score + " < " + context.getLowScoreThreshold()
            );
        }
    }
}
