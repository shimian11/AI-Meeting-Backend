package com.banma.interview.interview.application.runtime;

import cn.hutool.core.util.StrUtil;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 提供面试会话运行态懒恢复过程中的分布式互斥锁能力，
 * 用于避免同一会话在并发恢复时重复重建缓存或产生状态覆盖。
 */
@Service
@RequiredArgsConstructor
public class InterviewSessionRuntimeLockService {

    private static final String LOCK_KEY_PREFIX = "interview:runtime:rehydrate:lock:";
    /** 抢锁等待时长（0 = 不等待，抢不到即快速失败） */
    private static final long LOCK_WAIT_MILLIS = 0L;
    /** 锁租期（秒），到期自动释放，防止宕机死锁 */
    private static final long LOCK_LEASE_SECONDS = 60L;

    /** Redisson 客户端（分布式锁与限流） */
    private final RedissonClient redissonClient;

    /** 以默认等待时间（不等待）获取会话运行态恢复锁。 */
    public RLock acquire(String sessionId) throws InterruptedException {
        return acquire(sessionId, LOCK_WAIT_MILLIS);
    }

    /**
     * 获取会话运行态恢复锁，抢锁失败时返回 null。
     *
     * @param sessionId 面试会话 id
     * @param waitMillis 最长等待时间（毫秒），负数按 0 处理
     * @return 获取成功的锁对象，失败返回 null
     */
    public RLock acquire(String sessionId, long waitMillis) throws InterruptedException {
        // （1）会话 id 为空时直接返回，避免生成无意义锁键
        if (StrUtil.isBlank(sessionId)) {
            return null;
        }
        // （2）按会话维度构造锁键，并按等待时间尝试加锁
        RLock lock = redissonClient.getLock(LOCK_KEY_PREFIX + sessionId);
        boolean acquired = lock.tryLock(Math.max(waitMillis, 0L), LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
        return acquired ? lock : null;
    }

    /**
     * 释放当前线程持有的运行态恢复锁，锁为空或非本线程持有时忽略。
     */
    public void release(RLock lock) {
        // （1）仅当锁仍由当前线程持有时才解锁
        if (lock != null && lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }
}
