package com.banma.interview.interview.api.io.resp;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;

import java.util.Date;

/**
 * 报告列表项：面向「展示语义」，与存储语义的 {@link InterviewRecordRespDTO} 分离。
 *
 */
@Data
public class InterviewReportItemRespDTO {

    /** 会话 id，前端用它拼详情路由 */
    private String sessionId;

    /** 面试方向（原 interviewDirection） */
    private String direction;

    /** 创建时间，序列化成 "yyyy-MM-dd HH:mm"（原 createTime） */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm", timezone = "GMT+8")
    private Date createdAt;

    /** 总分 0~100（原 interviewScore） */
    private Integer totalScore;

    /** 题数 */
    private Integer questionCount;

    /** 展示状态：FINISHED（已完成/已评估）| ONGOING（进行中） */
    private String status;
}
