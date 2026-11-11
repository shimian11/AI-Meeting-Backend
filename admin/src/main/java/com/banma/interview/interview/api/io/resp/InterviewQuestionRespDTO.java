package com.banma.interview.interview.api.io.resp;

import lombok.Data;

import java.util.Date;
import java.util.Map;

@Data
public class InterviewQuestionRespDTO {

    /** 主键ID */
    private String id;

    /** 会话ID */
    private String sessionId;

    /** 用户名 */
    private String userName;

    /** 面试题目（题号 → 题目内容） */
    private Map<String, String> questions;

    /** 面试建议（编号 → 建议内容） */
    private Map<String, String> suggestions;

    /** 面试岗位方向 */
    private String interviewType;

    /** 简历文件URL */
    private String resumeFileUrl;

    /** AI响应耗时（毫秒） */
    private Integer responseTime;

    /** AI消耗的Token数 */
    private Integer tokenCount;

    /** 简历评分（0-100） */
    private Integer resumeScore;

    /** 题目数量 */
    private Integer questionCount;

    /** 建议数量 */
    private Integer suggestionCount;

    /** 提取是否成功（1成功 0失败） */
    private Integer isSuccess = 1;

    /** 错误信息（成功时为空） */
    private String errorMessage;

    /** 创建时间 */
    private Date createTime;

    /** 更新时间 */
    private Date updateTime;
}