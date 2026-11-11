package com.banma.interview.interview.application.guard.lock;

import cn.hutool.core.util.StrUtil;
import com.banma.interview.interview.config.InterviewAiSingleFlightConfiguration;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * Session-level lock for heavy AI operations.
 * 重耗时 AI 操作的会话级分布式锁，用于降低同一会话重复触发 AI 调用。
 */
@Service
@RequiredArgsConstructor
public class InterviewAiSessionLockService {

    private static final String LOCK_KEY_PREFIX = "interview:ai:heavy:lock:";

    /** Redisson 客户端（分布式锁与限流） */
    private final RedissonClient redissonClient;
    /** single-flight 配置 */
    private final InterviewAiSingleFlightConfiguration configuration;

    /**
     * 按 stage + sessionId 维度获取 AI 重调用锁，抢锁失败时返回 null。
     *
     * @param sessionId 面试会话 id
     * @param stage AI 调用阶段标识
     * @return 获取成功的锁对象，失败返回 null
     */
    public RLock acquire(String sessionId, String stage) throws InterruptedException {
        // （1）会话 id 或阶段为空时直接返回，避免生成无意义锁键
        if (StrUtil.isBlank(sessionId) || StrUtil.isBlank(stage)) {
            return null;
        }
        // （2）构造锁键并按配置的等待时间与租约时间抢锁
        RLock lock = redissonClient.getLock(LOCK_KEY_PREFIX + stage + ":" + sessionId);
        boolean acquired = lock.tryLock(
                resolveWaitMillis(),
                resolveExpireSeconds(),
                TimeUnit.SECONDS
        );
        return acquired ? lock : null;
    }

    /**
     * 释放当前线程持有的 AI 调用锁，锁为空或非本线程持有时忽略。
     */
    public void release(RLock lock) {
        // （1）仅当锁仍由当前线程持有时才解锁
        if (lock != null && lock.isHeldByCurrentThread()) {
            lock.unlock();
        }
    }

    /** 解析锁租约时间（秒），未配置或非法时默认 45 秒。 */
    private long resolveExpireSeconds() {
        Long configured = configuration.getHeavyLockExpireSeconds();
        return configured != null && configured > 0 ? configured : 45L;
    }

    /** 解析抢锁等待时间（毫秒），未配置或非法时默认不等待。 */
    private long resolveWaitMillis() {
        Long configured = configuration.getHeavyLockWaitMillis();
        return configured != null && configured >= 0 ? configured : 0L;
    }
}
