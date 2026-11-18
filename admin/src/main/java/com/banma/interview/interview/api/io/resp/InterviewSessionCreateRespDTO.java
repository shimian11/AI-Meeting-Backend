package com.banma.interview.interview.api.io.resp;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class InterviewSessionCreateRespDTO {

    /**
     * 会话唯一标识 ID。
     */
    private String sessionId;

    /**
     * 会话状态。
     */
    private String status;
}