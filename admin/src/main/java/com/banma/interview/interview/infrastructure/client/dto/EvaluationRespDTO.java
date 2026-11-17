package com.banma.interview.interview.infrastructure.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 面试评价响应结果 DTO
 *
 * <p>用于接收评价服务返回的评价结果。</p>
 */
@Data
public class EvaluationRespDTO {
    /**
     * 链路追踪 ID
     */
    @JsonProperty("trace_id")
    private String traceId;

    /**
     * 评价得分
     */
    @JsonProperty("score")
    private Integer score;

    /**
     * 评价反馈
     */
    @JsonProperty("feedback")
    private String feedback;

    /**
     * 遗漏要点
     */
    @JsonProperty("missing_points")
    private List<String> missingPoints;

    /**
     * 是否需要追问
     */
    @JsonProperty("follow_up_needed")
    private Boolean followUpNeeded;

    /**
     * 追问问题
     */
    @JsonProperty("follow_up_question")
    private String followUpQuestion;
}