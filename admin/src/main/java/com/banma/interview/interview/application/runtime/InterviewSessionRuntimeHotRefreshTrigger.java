package com.banma.interview.interview.application.runtime;

/**
 * 定义热快照刷新触发类型及其默认策略，
 * 用于区分普通中间态刷新与关键检查点立即刷新场景。
 */
public enum InterviewSessionRuntimeHotRefreshTrigger {

    // 题目抽取完成：普通快照级别，按防抖窗口刷新
    QUESTION_READY("QUESTION_READY", false, false, 10),
    // 神态评估完成：普通快照级别，按防抖窗口刷新
    DEMEANOR_READY("DEMEANOR_READY", false, false, 20),
    // 答案提交：关键检查点，立即刷盘并归档轮次
    ANSWER_COMMITTED("ACTIVE", true, true, 30),
    // 面试收尾：立即刷盘，轮次已归档因此不再重复归档
    FINALIZED("FINALIZED", true, false, 40);

    // 写入快照时使用的快照级别
    /** 目标快照级别（热/冷） */
    private final String snapshotLevel;

    // 是否要求立即刷盘（关键检查点）
    /** 是否强制立即刷新快照 */
    private final boolean forceFlush;

    // 是否把本轮问答写入轮次归档表
    /** 是否同时落轮次归档 */
    private final boolean persistTurnArchive;

    // 触发优先级，数值越大越优先
    /** 刷新优先级，数值越大越先处理 */
    private final int priority;

    InterviewSessionRuntimeHotRefreshTrigger(
            String snapshotLevel,
            boolean forceFlush,
            boolean persistTurnArchive,
            int priority) {
        this.snapshotLevel = snapshotLevel;
        this.forceFlush = forceFlush;
        this.persistTurnArchive = persistTurnArchive;
        this.priority = priority;
    }

    /** 返回该触发对应的快照级别。 */
    public String getSnapshotLevel() {
        return snapshotLevel;
    }

    /** 返回是否要求立即刷盘。 */
    public boolean isForceFlush() {
        return forceFlush;
    }

    /** 返回是否需要归档本轮问答。 */
    public boolean isPersistTurnArchive() {
        return persistTurnArchive;
    }

    /** 返回触发优先级。 */
    public int getPriority() {
        return priority;
    }
}
