package com.banma.interview.interview.service.model;

/**
 * 定义面试运行态恢复结果的置信度等级，
 * 用于标识恢复后的会话是否可写、是否仅可读以及是否处于终态。
 */
public enum InterviewRuntimeConfidence {

    EXACT,              // 精确恢复，数据来源为缓存或快照
    DERIVED,            // 推倒重建，从面试记录恢复数据，数据来源为面试记录
    READ_ONLY,          // 仅可读，数据来源为持久化主数据(如已结束的的面试）
    TERMINAL            // 终态，数据来源为持久化主数据(如已结束的的面试）
}
