package com.banma.interview.interview.service.model;

import lombok.Data;

/**
 * 定义面试运行态中的得分聚合结果对象，
 * 用于承载总分、累计得分和计分次数等恢复所需的聚合信息。
 */
@Data
public class InterviewRuntimeScoreAggregate {

    /**
     * 累计得分。
     */
    private Integer scoreSum;

    /**
     * 计分次数。
     */
    private Integer scoreCount;

    /**
     * 总分。
     */
    private Integer totalScore;
}