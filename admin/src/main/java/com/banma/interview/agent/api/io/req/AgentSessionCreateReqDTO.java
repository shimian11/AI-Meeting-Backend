package com.banma.interview.agent.api.io.req;

import lombok.Data;

@Data
public class AgentSessionCreateReqDTO {

    /**
     * 用户名，标识发起会话的用户。
     */
    private String userName;

    /**
     * 首条消息，创建会话时用户发送的第一条消息内容。
     */
    private String firstMessage;
}