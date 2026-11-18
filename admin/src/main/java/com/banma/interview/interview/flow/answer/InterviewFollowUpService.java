package com.banma.interview.interview.flow.answer;

import cn.hutool.core.util.StrUtil;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 面试追问服务：拿评分官给出的 follow_up_question 作为追问内容，按次数上限裁决。
 *
 * 不再调用任何 AI：评分官在评分时已针对本次答案生成追问建议
 */

@Service
@Slf4j
public class InterviewFollowUpService {

    /**
     * 生成追问问题：检查追问次数上限后优先调用工作流，失败时回退到评分器建议问题。
     *
     * @return 追问结果；超过上限或无可用的追问内容时返回空结果
     */
    public FollowUpQuestionResult generateFollowUpQuestion(
            String sessionId,
            String requestId,
            String currentQuestionNumber,
            String currentQuestion,
            String answerContent,
            String fallbackFollowUpQuestion,
            Integer currentFollowUpCount,
            Integer maxFollowUp) {

        // （1）先做追问次数与输入兜底，超过上限直接停止追问。
        int safeCurrentFollowUpCount = Math.max(0, currentFollowUpCount == null ? 0 : currentFollowUpCount);
        int safeMaxFollowUp = maxFollowUp == null || maxFollowUp <= 0 ? 2 : maxFollowUp;
        String sanitizedFallbackQuestion = sanitizeFollowUpQuestion(fallbackFollowUpQuestion);
        if (safeCurrentFollowUpCount >= safeMaxFollowUp) {
            return FollowUpQuestionResult.empty();
        }

        // （2）计算主问题编号与本次追问题号。
        String mainQuestionNumber = resolveMainQuestionNumber(currentQuestionNumber);
        String questionNumber = buildFollowUpQuestionNumber(mainQuestionNumber, safeCurrentFollowUpCount + 1);
        if (StrUtil.isBlank(questionNumber)) {
            return FollowUpQuestionResult.empty();
        }

        // （3）追问文本直接用评分官给出的建议问题
        //     评分官的 follow_up_question 已针对本次答案生成；旧追问工作流随星辰删除，
        //     再调一次只会白跑、抛异常、刷一条 warn
        String questionContent = sanitizedFallbackQuestion;

        return new FollowUpQuestionResult(questionNumber, questionContent, safeCurrentFollowUpCount + 1);
    }

    /**
     * 清洗追问文本：过滤无效占位值、补问号并截断到 100 字。
     */
    private String sanitizeFollowUpQuestion(String question) {
        // （1）空值直接返回
        if (StrUtil.isBlank(question)) {
            return null;
        }
        // （2）过滤 none/null/N/A 等无效占位值
        String normalized = question.trim();
        if ("none".equalsIgnoreCase(normalized)
                || "null".equalsIgnoreCase(normalized)
                || "N/A".equalsIgnoreCase(normalized)
                || "-".equals(normalized)
                || "__FINISH__".equalsIgnoreCase(normalized)) {
            return null;
        }
        if (!normalized.endsWith("?")) {
            normalized = normalized + "?";
        }
        // （3）统一截断长度，避免追问过长
        return clip(normalized, 100);
    }

    /**
     * 从追问题号中取回主问题号，如 2-F1 取 2。
     */
    private String resolveMainQuestionNumber(String questionNumber) {
        if (StrUtil.isBlank(questionNumber)) {
            return null;
        }
        String normalized = questionNumber.trim();
        int separatorIndex = normalized.indexOf("-F");
        if (separatorIndex > 0) {
            return normalized.substring(0, separatorIndex);
        }
        return normalized;
    }

    /**
     * 按“主问题号-F追问次数”拼接追问题号。
     */
    private String buildFollowUpQuestionNumber(String mainQuestionNumber, int followUpCount) {
        if (StrUtil.isBlank(mainQuestionNumber) || followUpCount <= 0) {
            return null;
        }
        return mainQuestionNumber + "-F" + followUpCount;
    }

    /**
     * 按最大长度截断文本，未超长时原样返回。
     */
    private String clip(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    /**
     * 追问生成结果：追问题号、追问内容与当前追问次数。
     */
    @Getter
    public static final class FollowUpQuestionResult {
        /** 题号 */
        private final String questionNumber;
        /** 题干内容 */
        private final String questionContent;
        /** 当前主问题已追问次数 */
        private final Integer followUpCount;

        private FollowUpQuestionResult(String questionNumber, String questionContent, Integer followUpCount) {
            this.questionNumber = questionNumber;
            this.questionContent = questionContent;
            this.followUpCount = followUpCount;
        }

        public static FollowUpQuestionResult empty() {
            return new FollowUpQuestionResult(null, null, 0);
        }

        /**
         * 是否成功生成可用的追问（题号与内容均非空）。
         */
        public boolean hasQuestion() {
            return StrUtil.isNotBlank(questionNumber) && StrUtil.isNotBlank(questionContent);
        }
    }
}


