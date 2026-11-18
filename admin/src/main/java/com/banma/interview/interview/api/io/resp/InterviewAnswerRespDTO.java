package com.banma.interview.interview.api.io.resp;

import lombok.Data;

import java.util.List;

/**
 * 面试题回答响应DTO
 */
@Data
public class InterviewAnswerRespDTO {
    
    /**
     * 题号
     */
    private String questionNumber;
    
    /**
     * 题目内容
     */
    private String questionContent;
    
    /**
     * 本次得分
     */
    private Integer score;
    
    /**
     * 累计总分
     */
    private Integer totalScore;
    
    /**
     * 是否成功
     */
    private Boolean isSuccess;
    
    /**
     * 错误信息
     */
    private String errorMessage;
    
    /**
     * AI评价内容（可选）
     */
    private String feedback;

    /**
     * 后端编排器生成的下一个问题内容
     */
    private String nextQuestion;

    /**
     * 主问题序列中的下一个问题编号
     */
    private String nextQuestionNumber;

    /**
     * 是否为追问问题
     */
    private Boolean isFollowUp;

    /**
     * 当前主问题的追问次数
     */
    private Integer followUpCount;

    /**
     * 是否已完成面试
     */
    private Boolean finished;

    /**
     * 缺失要点（逐条），供前端展示"没答到什么"。
     */
    private List<String> missingPoints;

    /**
     * 本题是否需要追问。
     *
     * <p>与 {@code isFollowUp} 的区别：{@code isFollowUp} 表示"当前这题本身是追问"，
     * 本字段表示"答完这题后是否应该继续追问"。</p>
     */
    private Boolean followUpNeeded;

    /**
     * 追问问题；{@code followUpNeeded=true} 时非空。
     */
    private String followUpQuestion;

    /**
     * 初始化默认响应，避免每次手动 set 基础状态。
     */
    public static InterviewAnswerRespDTO init() {
        InterviewAnswerRespDTO resp = new InterviewAnswerRespDTO();
        resp.setIsSuccess(false);           // 默认失败
        resp.setFinished(false);            // 默认未完成面试
        resp.setIsFollowUp(false);          // 默认不是追问问题
        resp.setFollowUpCount(0);           // 默认追问次数为0
        return resp;
    }

    /**
     * 标记失败并写入错误信息。
     */
    public InterviewAnswerRespDTO fail(String errorMessage) {
        this.setErrorMessage(errorMessage);
        this.setIsSuccess(false);
        return this;
    }

    /**
     * 标记成功。
     */
    public InterviewAnswerRespDTO success() {
        this.setIsSuccess(true);
        return this;
    }

    /**
     * 写入当前作答题目的元信息。
     */
    public InterviewAnswerRespDTO withCurrentQuestion(String questionNumber, String questionContent) {
        this.setQuestionNumber(questionNumber);
        this.setQuestionContent(questionContent);
        return this;
    }

    /**
     * 写入评分与反馈信息。
     */
    public InterviewAnswerRespDTO withEvaluation(Integer score, String feedback, Integer totalScore) {
        this.setScore(score);
        this.setFeedback(feedback);
        this.setTotalScore(totalScore);
        return this;
    }

    /**
     * 写入下一题信息（可用于主问题或追问）。
     */
    public InterviewAnswerRespDTO withNextQuestion(
            String nextQuestionNumber,
            String nextQuestion,
            boolean isFollowUp,
            Integer followUpCount) {
        this.setNextQuestionNumber(nextQuestionNumber);
        this.setNextQuestion(nextQuestion);
        this.setIsFollowUp(isFollowUp);
        this.setFollowUpCount(followUpCount == null ? 0 : followUpCount);
        this.setFinished(false);
        return this;
    }

    /**
     * 标记面试结束并清理下一题字段。
     */
    public InterviewAnswerRespDTO finish() {
        this.setFinished(true);
        this.setIsFollowUp(false);
        this.setFollowUpCount(0);
        this.setNextQuestion(null);
        this.setNextQuestionNumber(null);
        return this;
    }
}
