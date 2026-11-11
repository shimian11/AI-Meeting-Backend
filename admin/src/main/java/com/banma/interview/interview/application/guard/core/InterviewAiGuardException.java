package com.banma.interview.interview.application.guard.core;

import lombok.Getter;

/**
 * AI保护层引发异常
 */
@Getter
public class InterviewAiGuardException extends RuntimeException {

    /** AI 治理错误码 */
    private final InterviewAiGuardErrorCode errorCode;
    /** 治理阶段（如答题/神态/提取） */
    private final String stage;

    public InterviewAiGuardException(
            InterviewAiGuardErrorCode errorCode,
            String stage,
            String message,
            Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
        this.stage = stage;
    }
}
