package com.banma.interview.agent.api.io.resp;

import lombok.Data;

import java.util.Date;

@Data
public class AgentConversationRespDTO {

    /**
     * 会话唯一标识 ID。
     */
    private String sessionId;

    /**
     * Agent 名称。
     */
    private String agentName;

    /**
     * 会话标题。
     */
    private String conversationTitle;

    /**
     * 消息数量。
     */
    private Integer messageCount;

    /**
     * 累计消耗的 Token 数量。
     */
    private Integer totalTokens;

    /**
     * 会话状态。
     */
    private Integer status;

    /**
     * 创建时间。
     */
    private Date createTime;

    /**
     * 更新时间。
     */
    private Date updateTime;
}