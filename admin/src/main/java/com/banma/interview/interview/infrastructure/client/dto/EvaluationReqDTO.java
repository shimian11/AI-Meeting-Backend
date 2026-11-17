package com.banma.interview.interview.infrastructure.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * 面试评价请求参数 DTO
 *
 * <p>用于向评价服务发起评价请求时传递的请求体参数。</p>
 */
@Data
public class EvaluationReqDTO {
    /**
     * 链路追踪 ID
     */
    @JsonProperty("trace_id")
    private String traceId;

    /**
     * 会话 ID
     */
    @JsonProperty("session_id")
    private String sessionId;

    /**
     * 面试问题
     */
    @JsonProperty("question")
    private String question;

    /**
     * 候选人回答
     */
    @JsonProperty("answer")
    private String answer;

    /**
     * 简历摘要
     */
    @JsonProperty("resume_digest")
    private List<String> resumeDigest;

    /**
     * 评价标准
     */
    @JsonProperty("standard")
    private List<String> standard;
}