package com.banma.interview.interview.api.io.resp;

import lombok.Data;

import java.util.Map;

@Data
public class InterviewSessionRestoreRespDTO {

    /**
     * 会话唯一标识 ID。
     */
    private String sessionId;

    /**
     * 会话状态。
     */
    private String status;

    /**
     * 是否可继续面试。
     */
    private Boolean canResume;

    /**
     * 简历文件访问地址。
     */
    private String resumeFileUrl;

    /**
     * 简历评分。
     */
    private Integer resumeScore;

    /**
     * 面试类型。
     */
    private String interviewType;

    /**
     * 改进建议，键值对形式。
     */
    private Map<String, String> suggestions;
}