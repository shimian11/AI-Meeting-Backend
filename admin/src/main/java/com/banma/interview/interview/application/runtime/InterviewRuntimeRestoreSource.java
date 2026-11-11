package com.banma.interview.interview.application.runtime;

/**
 * 定义面试运行态懒恢复时可选的数据恢复来源，
 * 用于标识当前会话数据来自缓存、快照、报告或持久化主数据回补。
 */
public enum InterviewRuntimeRestoreSource {

    CACHE,                      // 从缓存恢复数据
    RUNTIME_SNAPSHOT,           // 从运行照恢复数据
    INTERVIEW_RECORD,           // 从面试记录恢复数据
    SESSION_QUESTION,           // 从持久化主数据返回数据
    NONE                        // 无数据来源，直接返回空数据
}
