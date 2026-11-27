package com.banma.interview.common.enums;

import com.banma.interview.common.convention.errorcode.IErrorCode;

/**
 * 面试域错误码（B0004xx）。
 */
public enum InterviewErrorCodeEnum implements IErrorCode {

    SESSION_ID_EMPTY("B000400", "会话 ID 不能为空"),
    INVALID_USER_ID("B000401", "用户 ID 无效"),
    CONVERSATION_NOT_FOUND("B000402", "对话记录不存在"),
    CONVERSATION_ACCESS_DENIED("B000403", "无权访问该对话"),
    INTERVIEW_SESSION_NOT_FOUND("B000412", "面试会话不存在"),
    INTERVIEW_SESSION_ACCESS_DENIED("B000413", "无权访问该面试会话"),
    INTERVIEW_SESSION_INVALID_STATE("B000414", "面试会话状态异常，请重新开始面试"),
    AI_TIMEOUT("B000415", "AI 调用超时，请重试"),
    AI_OVERLOADED("B000416", "AI 服务繁忙，请稍后重试"),
    AI_UNAVAILABLE("B000417", "AI 服务暂时不可用，请稍后重试"),

    AGENT_CONFIG_NOT_FOUND("B000406", "智能体配置不存在，请联系管理员");

    private final String code;
    private final String message;

    InterviewErrorCodeEnum(String code, String message) {
        this.code = code;
        this.message = message;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }
}
