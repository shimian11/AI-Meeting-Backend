package com.banma.interview.interview.application.finalize;

import cn.hutool.core.util.StrUtil;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Session-level lock used by interview finalize flow to avoid duplicate finish/save races.
 * 收尾流程使用的会话级分布式锁，避免同一会话的 finish/save 并发执行。
 */
@Service
public class InterviewFinalizeLockService {

    private static final String LOCK_KEY_PREFIX = "interview:finalize:lock:";
    /** 抢锁等待时长（0 = 不等待，抢不到即快速失败） */
    private static final long LOCK_WAIT_MILLIS = 0L;
    /** 锁租期（秒），到期自动释放，防止宕机死锁 */
    private static final long LOCK_LEASE_SECONDS = 120L;

    /** Redisson 客户端（分布式锁与限流） */
    private final RedissonClient redissonClient;

    public InterviewFinalizeLockService(RedissonClient redissonClient) {
        this.redissonClient = redissonClient;
    }

    /**
     * 尝试获取指定会话的收尾锁，sessionId 为空或抢锁失败时返回 null。
     *
     * @param sessionId 面试会话 id
     * @return 获取成功的锁对象，失败返回 null
     */
    public RLock acquire(String sessionId) throws InterruptedException {
        // （1）会话 id 为空时直接返回，避免生成无意义的锁键
        if (StrUtil.isBlank(sessionId)) {
            return null;
        }
        // （2）按会话维度构造锁键，不等待且租约 120 秒
        RLock lock = redissonClient.getLock(LOCK_KEY_PREFIX + sessionId);
        // （3）tryLock 失败返回 null，由调用方决定是否放弃本次收尾
        boolean acquired = lock.tryLock(LOCK_WAIT_MILLIS, LOCK_LEASE_SECONDS, TimeUnit.SECONDS);
        return acquired ? lock : null;
    }

    /**
     * 释放当前线程持有的收尾锁，锁为空或不属于当前线程时忽略。
     */
    public void release(RLock lock) {
        // （1）仅当锁仍由当前线程持有时才解锁，避免误释放其他线程的锁
        if (lock != null && lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }
}
