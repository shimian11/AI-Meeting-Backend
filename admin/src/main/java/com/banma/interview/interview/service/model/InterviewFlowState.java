package com.banma.interview.interview.service.model;

import lombok.Data;

/**
 * Interview flow state cached in Redis.
 */
@Data
public class InterviewFlowState {

    /** 流程状态（如 COMPLETED 表示全部题目已答完） */
    private String status;

    /** 当前题目游标位置（从0开始） */
    private Integer currentIndex;

    /** 当前题号（如 "Q3" 或 "3-F1" 追问题） */
    private String currentQuestionNumber;

    /** 面试题目总数 */
    private Integer totalQuestions;

    /** 当前追问次数 */
    private Integer followUpCount;

    /** 每题最大追问次数 */
    private Integer maxFollowUp;

    /** 乐观锁版本号 */
    private Integer version;

    /** 判断当前面试流程是否已完成 */
    public boolean isCompleted() {
        return "COMPLETED".equalsIgnoreCase(status);
    }
}