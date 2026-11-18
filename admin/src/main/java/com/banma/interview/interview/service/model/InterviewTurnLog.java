package com.banma.interview.interview.service.model;

import lombok.Builder;
import lombok.Data;

/**
 * 面试单轮日志结构体（用于 Redis 缓存与快照持久化）。
 */
@Data
@Builder
public class InterviewTurnLog {

    /**
     * 时间戳。
     */
    private Long timestamp;

    /**
     * 请求唯一标识 ID。
     */
    private String requestId;

    /**
     * 问题编号。
     */
    private String questionNumber;

    /**
     * 问题内容。
     */
    private String questionContent;

    /**
     * 回答内容。
     */
    private String answerContent;

    /**
     * 本轮得分。
     */
    private Integer score;

    /**
     * 累计总分。
     */
    private Integer totalScore;

    /**
     * 反馈信息。
     */
    private String feedback;

    /**
     * 是否需要追问。
     */
    private Boolean followUpNeeded;

    /**
     * 是否为追问。
     */
    private Boolean isFollowUp;

    /**
     * 追问次数。
     */
    private Integer followUpCount;

    /**
     * 下一题编号。
     */
    private String nextQuestionNumber;

    /**
     * 下一题内容。
     */
    private String nextQuestion;

    /**
     * 面试是否结束。
     */
    private Boolean finished;
}