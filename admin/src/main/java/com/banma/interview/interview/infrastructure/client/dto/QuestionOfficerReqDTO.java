package com.banma.interview.interview.infrastructure.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

/**
 * Python 出题官请求体，字段严格对齐
 */
@Data
public class QuestionOfficerReqDTO {

    /** 链路追踪 id：由 Java 生成并透传，Python 会原样回显 */
    @JsonProperty("trace_id")
    private String traceId;

    /** 面试会话 id */
    @JsonProperty("session_id")
    private String sessionId;

    /** 本轮用户话术 */
    @JsonProperty("user_input")
    private String userInput;

    /** 简历预签名 URL；无简历时为 null */
    @JsonProperty("resume_url")
    private String resumeUrl;

    /** 前端声明的方向；null 表示交由 Python 走四级语义归一 */
    @JsonProperty("direction_default")
    private String directionDefault;

    /** 历史轮次摘要，供出题去重 */
    @JsonProperty("history_digest")
    private List<String> historyDigest = new ArrayList<>();
}
