package com.banma.interview.interview.api.io.resp;

import lombok.Data;

import java.util.List;

/**
 * Structured end-of-interview review feedback for report rendering.
 */
@Data
public class InterviewReviewFeedbackRespDTO {

    /**
     * 整体评价，对整场面试的综合总结性评语。
     */
    private String overallComment;

    /**
     * 亮点，面试中表现突出的方面。
     */
    private List<String> highlights;

    /**
     * 改进建议，面试中可以进一步优化的方面。
     */
    private List<String> improvementTips;

    /**
     * 后续行动建议，面试后建议采取的下一步行动。
     */
    private List<String> nextActions;
}