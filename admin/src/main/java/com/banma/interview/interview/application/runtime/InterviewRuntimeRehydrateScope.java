package com.banma.interview.interview.application.runtime;

/**
 * 定义面试会话运行态懒恢复的范围枚举，
 * 用于区分只恢复流程、得分、回放、材料或整包运行态等不同场景。
 */
public enum InterviewRuntimeRehydrateScope {

    FLOW_ONLY,                      // 只恢复流程,加载题目材料+流程+追问
    SCORE_ONLY,                     // 只恢复得分,得分
    PLAYBACK_ONLY,                  // 只恢复回放,加载轮次+请求ID
    MATERIAL_ONLY,                  // 只恢复材料,加载题目材料+建议材料+简历材料
    HOT_RUNTIME,                    // 恢复热数据，除建议材料、简历材料外的全部（题目材料+流程+追问+得分+轮次+请求ID）
    FULL_RUNTIME;                   // 恢复全部数据态，加载所有数据（题目材料+流程+追问+得分+轮次+请求ID+建议材料+简历材料）

    public boolean includesQuestionMaterial() {
        return switch (this) {
            case FLOW_ONLY, MATERIAL_ONLY, HOT_RUNTIME, FULL_RUNTIME -> true;
            default -> false;
        };
    }

    public boolean includesSuggestionMaterial() {
        return this == MATERIAL_ONLY || this == FULL_RUNTIME;
    }

    public boolean includesResumeMaterial() {
        return this == MATERIAL_ONLY || this == FULL_RUNTIME;
    }

    public boolean includesFlow() {
        return this == FLOW_ONLY || this == HOT_RUNTIME || this == FULL_RUNTIME;
    }

    public boolean includesFollowUpQuestions() {
        return this == FLOW_ONLY || this == HOT_RUNTIME || this == FULL_RUNTIME;
    }

    public boolean includesScore() {
        return this == SCORE_ONLY || this == HOT_RUNTIME || this == FULL_RUNTIME;
    }

    public boolean includesTurns() {
        return this == PLAYBACK_ONLY || this == HOT_RUNTIME || this == FULL_RUNTIME;
    }

    public boolean includesRequestIds() {
        return includesTurns();
    }
}
