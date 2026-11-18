package com.banma.interview.interview.api.io.resp;

import lombok.Data;

import java.util.Date;

@Data
public class InterviewConversationRespDTO {

    /**
     * 会话唯一标识 ID。
     */
    private String sessionId;

    /**
     * 会话标题。
     */
    private String conversationTitle;

    /**
     * 会话状态。
     */
    private String status;

    /**
     * 面试类型。
     */
    private String interviewType;

    /**
     * 简历文件访问地址。
     */
    private String resumeFileUrl;

    /**
     * 创建时间。
     */
    private Date createTime;

    /**
     * 更新时间。
     */
    private Date updateTime;
}