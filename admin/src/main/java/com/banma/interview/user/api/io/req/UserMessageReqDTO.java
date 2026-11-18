package com.banma.interview.user.api.io.req;

import lombok.Data;

@Data
public class UserMessageReqDTO {

    /**
     * 用户名。
     */
    private String userName;

    /**
     * 用户输入的消息内容。
     */
    private String inputMessage;

    /**
     * 消息序号，用于标识消息在会话中的顺序。
     */
    private int messageSeq;

    /**
     * 会话唯一标识 ID。
     */
    private String sessionId;
}