package com.banma.interview.interview.application.rule.node;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.banma.interview.interview.application.rule.InterviewFollowUpRuleContext;
import com.yomahub.liteflow.annotation.LiteflowComponent;
import com.yomahub.liteflow.core.NodeComponent;

/**
 * LiteFlow 节点：存在遗漏要点或追问提示时标记需要追问。
 */
@LiteflowComponent("missingPointsJudge")
public class MissingPointsJudgeNode extends NodeComponent {

    /**
     * 上下文存在遗漏要点或追问提示时，标记需要追问。
     */
    @Override
    public void process() {
        // （1）获取当前会话的规则上下文
        InterviewFollowUpRuleContext context = getContextBean(InterviewFollowUpRuleContext.class);
        // （2）已终止的流程不再继续判定
        if (context.isTerminated()) {
            return;
        }
        // （3）判断是否存在遗漏要点或追问提示
        boolean hasMissingPoints = CollUtil.isNotEmpty(context.getMissingPoints());
        boolean hasFollowUpHint = StrUtil.isNotBlank(context.getFollowUpQuestionHint());
        // （4）满足任一条件则标记需要追问
        if (hasMissingPoints || hasFollowUpHint) {
            context.markNeedFollowUp("MISSING_POINTS", "missing points or follow-up hint detected");
        }
    }
}
