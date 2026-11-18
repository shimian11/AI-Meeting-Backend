package com.banma.interview.agent.api.io.resp;

import lombok.Data;

@Data
public class AgentMessageRespDTO {

    /**
     * 会话唯一标识 ID。
     */
    private String sessionId;

    /**
     * 用户名。
     */
    private String userName;

    /**
     * 用户发送的消息内容。
     */
    private String userMessage;

    /**
     * AI 回复的消息内容。
     */
    private String chatMessage;

    /**
     * 消息序号，用于标识消息在会话中的顺序。
     */
    private Integer messageSeq;

    /**
     * 本条消息消耗的 Token 数量。
     */
    private Integer tokenCount;

    /**
     * 响应耗时（毫秒）。
     */
    private Integer responseTime;

    /**
     * 是否成功，默认 1 表示成功，0 表示失败。
     */
    private Integer isSuccess = 1;

    /**
     * 失败时的错误信息。
     */
    private String errorMessage;
}