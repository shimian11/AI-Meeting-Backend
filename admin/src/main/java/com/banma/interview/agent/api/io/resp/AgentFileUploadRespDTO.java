package com.banma.interview.agent.api.io.resp;

import lombok.Data;

import java.util.Date;

@Data
public class AgentFileUploadRespDTO {

    /**
     * 文件记录唯一标识 ID。
     */
    private Long id;

    /**
     * 会话唯一标识 ID。
     */
    private String sessionId;

    /**
     * 业务类型，用于区分文件所属的业务场景。
     */
    private String bizType;

    /**
     * 文件名。
     */
    private String fileName;

    /**
     * 文件大小（字节）。
     */
    private Long fileSize;

    /**
     * 文件内容类型（MIME 类型）。
     */
    private String contentType;

    /**
     * 文件访问地址。
     */
    private String fileUrl;

    /**
     * 创建时间。
     */
    private Date createTime;
}