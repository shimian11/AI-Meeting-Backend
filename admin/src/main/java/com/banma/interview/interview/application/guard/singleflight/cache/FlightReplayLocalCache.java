package com.banma.interview.interview.application.guard.singleflight.cache;

import cn.hutool.core.util.StrUtil;
import com.banma.interview.interview.config.InterviewAiSingleFlightConfiguration;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 结果的本地 L1 回放缓存，用于在短时间内直接复用已成功返回的结果，
 * 减少 follower 或重复请求再次访问 Redis 的开销。
 */
@Component
public class FlightReplayLocalCache {

    private final Map<String, CacheEntry> cache = new LinkedHashMap<>(128, 0.75F, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, CacheEntry> eldest) {
            return size() > maxSize;
        }
    };

    private volatile int maxSize = 1000;

    /**
     * 按配置刷新本地缓存的最大条目数，未配置时保持默认 1000。
     */
    public synchronized void refreshMaxSize(Integer configuredMaxSize) {
        // （1）配置值非法时回落到默认上限
        this.maxSize = configuredMaxSize != null && configuredMaxSize > 0 ? configuredMaxSize : 1000;
    }

    /**
     * 从本地缓存读取结果，条目不存在或已过期时返回 null。
     */
    public synchronized String get(String stage, String requestKey) {
        // （1）按 stage 与 requestKey 生成缓存键并读取条目
        String cacheKey = cacheKey(stage, requestKey);
        CacheEntry entry = cache.get(cacheKey);
        if (entry == null) {
            return null;
        }
        // （2）条目已过期时清除并返回 null
        if (entry.expireAtMillis <= System.currentTimeMillis()) {
            cache.remove(cacheKey);
            return null;
        }
        return entry.value;
    }

    /**
     * 写入本地缓存，仅在阶段策略开启 L1 缓存时生效。
     *
     * @param value 可回放的结果内容
     * @param policy 该阶段的 single-flight 策略（含 L1 开关与 TTL）
     */
    public synchronized void put(String stage, String requestKey, String value,
                                 InterviewAiSingleFlightConfiguration.StageFlightPolicy policy) {
        // （1）参数不全或该阶段未开启 L1 缓存时跳过写入
        if (StrUtil.isBlank(stage) || StrUtil.isBlank(requestKey) || value == null || policy == null
                || !Boolean.TRUE.equals(policy.getL1CacheEnabled())) {
            return;
        }
        // （2）解析 TTL（默认 30 秒）并写入带过期时间的条目
        long ttlMillis = policy.getL1CacheTtlMillis() == null || policy.getL1CacheTtlMillis() <= 0
                ? 30000L
                : policy.getL1CacheTtlMillis();
        cache.put(cacheKey(stage, requestKey), new CacheEntry(value, System.currentTimeMillis() + ttlMillis));
    }

    /** 拼接本地缓存键，空值以 "-" 占位。 */
    private String cacheKey(String stage, String requestKey) {
        return StrUtil.blankToDefault(stage, "-") + "|" + StrUtil.blankToDefault(requestKey, "-");
    }

    /**
     * 本地缓存条目，保存可回放内容及其失效时间。
     */
    private record CacheEntry(String value, long expireAtMillis) {
    }
}
