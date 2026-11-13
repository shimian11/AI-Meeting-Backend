package com.banma.interview.interview.infrastructure.client.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;
import java.util.Map;

/**
 * Python 出题官响应体，字段严格对齐
 */
@Data
public class QuestionOfficerRespDTO {

    /** 链路追踪 id，与请求一一对应，Python 原样回显 */
    @JsonProperty("trace_id")
    private String traceId;

    /** GENERATE（出题）| SMALL_TALK（闲聊兜底） */
    @JsonProperty("intent")
    private String intent;

    /** 生成的面试题目列表 */
    @JsonProperty("questions")
    private List<String> questions;

    /** 简历评分 */
    @JsonProperty("resume_score")
    private Integer resumeScore;

    /** 简历优化建议列表 */
    @JsonProperty("resume_suggest")
    private List<String> resumeSuggest;

    /** 简历类型 */
    @JsonProperty("resume_type")
    private String resumeType;

    /** kb+resume | resume_only | none —— 题目来源，控制台/排查用 */
    @JsonProperty("questions_source")
    private String questionsSource;

    /** 闲聊兜底回复内容 */
    @JsonProperty("small_talk")
    private String smallTalk;

    /** 各步骤耗时与命中的调试信息，仅用于观测，不参与业务判断 */
    @JsonProperty("steps_trace")
    private Map<String, Object> stepsTrace;
}