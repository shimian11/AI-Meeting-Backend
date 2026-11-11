package com.banma.interview.interview.flow.answer;

import cn.hutool.core.util.StrUtil;
import com.banma.interview.interview.config.InterviewAnswerGuardConfiguration;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Distributed lock guard for one interview question per session.
 * 会话内单题答题的分布式锁保护：同一 session 的同一题号串行处理。
 */
@Service
@RequiredArgsConstructor
public class InterviewQuestionLockService {

    private static final String LOCK_KEY_PREFIX = "interview:answer:lock:";

    /** Redisson 客户端（分布式锁与限流） */
    private final RedissonClient redissonClient;
    /** 答题防抖与限流配置 */
    private final InterviewAnswerGuardConfiguration configuration;

    /**
     * 按会话与题号获取分布式锁，未抢到锁时返回 null 由调用方快速失败。
     *
     * @param sessionId     面试会话 ID
     * @param questionNumber 当前题号
     * @return 抢锁成功返回锁对象，失败返回 null
     */
    public RLock acquire(String sessionId, String questionNumber) throws InterruptedException {
        // （1）参数为空时不做加锁
        if (StrUtil.isBlank(sessionId) || StrUtil.isBlank(questionNumber)) {
            return null;
        }
        // （2）按配置选择 watchdog 续期模式或固定过期时间模式
        RLock lock = redissonClient.getLock(lockKey(sessionId, questionNumber));
        boolean acquired;
        if (useWatchdogLock()) {
            acquired = lock.tryLock(resolveWaitMillis(), -1L, TimeUnit.MILLISECONDS);
        } else {
            acquired = lock.tryLock(resolveWaitMillis(), resolveExpireMillis(), TimeUnit.MILLISECONDS);
        }
        return acquired ? lock : null;
    }

    /**
     * 释放锁；仅当锁由当前线程持有时才执行解锁，避免误释放他人锁。
     */
    public void release(RLock lock) {
        // （1）锁非空且由当前线程持有才解锁
        if (lock != null && lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }

    /**
     * 拼接题目锁 key：前缀 + 会话 ID + 题号。
     */
    private String lockKey(String sessionId, String questionNumber) {
        return LOCK_KEY_PREFIX + sessionId + ":" + questionNumber;
    }

    /**
     * 解析锁的固定过期毫秒数，未配置时默认 120 秒。
     */
    private long resolveExpireMillis() {
        Long configured = configuration.getLockExpireSeconds();
        long seconds = configured == null || configured <= 0 ? 120L : configured;
        return TimeUnit.SECONDS.toMillis(seconds);
    }

    /**
     * 解析抢锁等待毫秒数，未配置或非法时默认不等待。
     */
    private long resolveWaitMillis() {
        Long configured = configuration.getLockWaitMillis();
        return configured == null || configured < 0 ? 0L : configured;
    }

    /**
     * 判断是否启用 watchdog 自动续期（需开启开关且未配置固定过期时间）。
     */
    private boolean useWatchdogLock() {
        return Boolean.TRUE.equals(configuration.getLockWatchdogEnabled())
                && (configuration.getLockExpireSeconds() == null || configuration.getLockExpireSeconds() <= 0);
    }
}
