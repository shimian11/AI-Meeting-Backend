package com.banma.interview.interview.application.rule;

import cn.hutool.core.util.StrUtil;
import com.banma.interview.interview.config.InterviewRuleEngineConfiguration;
import com.yomahub.liteflow.core.FlowExecutor;
import com.yomahub.liteflow.flow.LiteflowResponse;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 基于LiteFlow的跟踪规则服务.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class InterviewFollowUpRuleService {

    /** LiteFlow 流程执行器（规则链入口） */
    private final FlowExecutor flowExecutor;
    /** 追问规则引擎配置 */
    private final InterviewRuleEngineConfiguration ruleConfiguration;

    /**
     * 执行追问规则判定：补全上下文、按 LiteFlow 链路执行判定节点，失败时按配置回退。
     *
     * @param context 追问规则上下文
     * @return 追问规则决策
     */
    public InterviewFollowUpRuleDecision decide(InterviewFollowUpRuleContext context) {
        // （1）上下文缺失时直接返回无需追问的兜底决策
        if (context == null) {
            return InterviewFollowUpRuleDecision.noFollowUp(
                    Math.max(resolveDefaultMaxFollowUp(), 1),
                    "RULE_CONTEXT_MISSING",
                    "rule context is missing",
                    resolveDefaultChainId(),
                    ruleConfiguration.getRuleVersion(),
                    true
            );
        }

        // （2）补全链路 id、规则版本、追问上限等上下文
        hydrateContext(context);
        // （3）规则引擎未启用时走兜底策略
        if (!Boolean.TRUE.equals(ruleConfiguration.getEnable())) {
            return fallbackDecision(context, "RULE_ENGINE_DISABLED", "rule engine disabled");
        }

        try {
            // （4）执行 LiteFlow 规则链；失败时转为异常统一处理
            LiteflowResponse response = executeChain(context.getChainId(), context);
            if (response == null || !response.isSuccess()) {
                Throwable cause = response == null ? null : response.getCause();
                throw new IllegalStateException("LiteFlow execution failed", cause);
            }
            // （5）固化最终决策并返回
            context.finalizeDecision(false);
            return context.getDecision();
        } catch (Exception ex) {
            // （6）执行异常：failOpen 时回退旧策略，否则向上抛出
            if (Boolean.TRUE.equals(ruleConfiguration.getFailOpen())) {
                log.warn(
                        "LiteFlow follow-up decision failed, fallback to legacy policy, sessionId={}, chainId={}, questionNumber={}",
                        context.getSessionId(),
                        context.getChainId(),
                        context.getQuestionNumber(),
                        ex
                );
                return fallbackDecision(context, "RULE_ENGINE_FALLBACK", "fallback to legacy follow-up policy");
            }
            throw new IllegalStateException("LiteFlow follow-up decision failed", ex);
        }
    }

    /**
     * 执行指定 id 的 LiteFlow 规则链，返回链路执行结果。
     */
    LiteflowResponse executeChain(String chainId, InterviewFollowUpRuleContext context) {
        return flowExecutor.execute2Resp(chainId, null, context);
    }

    /**
     * 用规则配置补全上下文：链路 id、规则版本、追问上限、低分阈值与初始决策。
     */
    private void hydrateContext(InterviewFollowUpRuleContext context) {
        // （1）写入链路 id 与规则版本
        context.setChainId(resolveDefaultChainId());
        context.setRuleVersion(ruleConfiguration.getRuleVersion());
        // （2）解析追问上限与低分阈值
        context.setResolvedMaxFollowUp(resolveMaxFollowUp(context.getMaxFollowUp()));
        context.setLowScoreThreshold(resolveLowScoreThreshold());
        // （3）初始化决策对象与终止标记
        context.setDecision(new InterviewFollowUpRuleDecision());
        context.setTerminated(false);
    }

    /**
     * 构造兜底决策：结合 AI 建议与追问次数上限判断是否继续追问。
     *
     * @param context 规则上下文
     * @param fallbackReasonCode 兜底原因码
     * @param fallbackReasonText 兜底原因描述
     * @return 兜底追问决策
     */
    private InterviewFollowUpRuleDecision fallbackDecision(
            InterviewFollowUpRuleContext context,
            String fallbackReasonCode,
            String fallbackReasonText) {
        // （1）计算解析后的追问上限，并判断是否仍可追问
        int resolvedMax = resolveMaxFollowUp(context.getMaxFollowUp());
        boolean underLimit = context.getFollowUpCount() < resolvedMax;
        boolean needFollowUp = context.isFollowUpNeededFromAi() && underLimit;
        // （2）根据是否追问选择原因码与原因描述
        String reasonCode = needFollowUp
                ? "AI_SUGGESTED"
                : (underLimit ? fallbackReasonCode : "FOLLOW_UP_LIMIT_REACHED");
        String reasonText = needFollowUp
                ? "follow_up_needed from ai result"
                : (underLimit ? fallbackReasonText : "follow-up limit reached");
        // （3）构造默认无追问决策，再按判定结果覆盖是否追问标记
        InterviewFollowUpRuleDecision decision = InterviewFollowUpRuleDecision.noFollowUp(
                resolvedMax,
                reasonCode,
                reasonText,
                resolveDefaultChainId(),
                ruleConfiguration.getRuleVersion(),
                true
        );
        decision.setNeedFollowUp(needFollowUp);
        return decision;
    }

    /**
     * 解析默认链路 id，未配置时返回内置链路名。
     */
    private String resolveDefaultChainId() {
        return StrUtil.isNotBlank(ruleConfiguration.getDefaultChainId())
                ? ruleConfiguration.getDefaultChainId()
                : "default_followup_chain";
    }

    /**
     * 解析追问上限：优先使用入参，否则取配置默认值。
     */
    private int resolveMaxFollowUp(int fallbackMaxFollowUp) {
        if (fallbackMaxFollowUp > 0) {
            return fallbackMaxFollowUp;
        }
        return resolveDefaultMaxFollowUp();
    }

    /**
     * 解析配置中的默认追问上限，未配置或非法时取 2。
     */
    private int resolveDefaultMaxFollowUp() {
        Integer configured = ruleConfiguration.getDefaultMaxFollowUp();
        return configured != null && configured > 0 ? configured : 2;
    }

    /**
     * 解析配置中的低分阈值，未配置或非法时取 60。
     */
    private int resolveLowScoreThreshold() {
        Integer defaultThreshold = ruleConfiguration.getDefaultLowScoreThreshold();
        return defaultThreshold != null && defaultThreshold >= 0 ? defaultThreshold : 60;
    }
}
