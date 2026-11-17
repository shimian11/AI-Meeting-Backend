package com.banma.interview.interview.api.io.resp;

import lombok.Data;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 面试记录响应 DTO。
 */
@Data
public class InterviewRecordRespDTO {

    /**
     * 面试记录 ID
     */
    private Long id;

    /**
     * 用户 ID
     */
    private Long userId;

    /**
     * 会话 ID
     */
    private String sessionId;

    /**
     * 面试得分
     */
    private Integer interviewScore;

    /**
     * 简历得分
     */
    private Integer resumeScore;

    /**
     * INIT / IN_PROGRESS / FINISHED / EVALUATED
     */
    private String interviewStatus;

    /**
     * 问题数量
     */
    private Integer questionCount;

    /**
     * 面试官智能体 ID
     */
    private Long interviewerAgentId;

    /**
     * 原始建议串（分号分隔）。
     */
    private String interviewSuggestions;

    /**
     * 建议结构化映射（编号 -> 建议文本）。
     */
    private Map<String, String> interviewSuggestionsMap;

    /**
     * 面试方向
     */
    private String interviewDirection;

    /**
     * 开始时间
     */
    private Date startTime;

    /**
     * 结束时间
     */
    private Date endTime;

    /**
     * 持续时长（秒）
     */
    private Integer durationSeconds;

    /**
     * 历史快照原文，兼容旧前端。
     */
    private String sessionSnapshotJson;

    /**
     * 雷达图聚合分值（结构化）。
     */
    private RadarChartDTO radarChart;

    /**
     * 雷达图维度列表（前端可直接绘图）。
     */
    private List<RadarDimensionItemRespDTO> radarDimensions;

    /**
     * 面试问答回放列表。
     */
    private List<InterviewPlaybackItemRespDTO> playbackItems;

    /**
     * 面试回顾反馈
     */
    private InterviewReviewFeedbackRespDTO reviewFeedback;

    /**
     * 创建时间
     */
    private Date createTime;

    /**
     * 更新时间
     */
    private Date updateTime;
}