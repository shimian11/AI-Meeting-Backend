package com.banma.interview.interview.service.cache;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.concurrent.TimeUnit;

/**
 * 基于 Redis 的面试缓存存取实现，统一封装字符串读写、自增、过期与删除操作。
 */
@Component
@RequiredArgsConstructor
public class RedisInterviewCacheStore implements InterviewCacheStore {

    /** Redis 模板，用于缓存读写 */
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 读取指定 key 的字符串值。
     */
    @Override
    public String getValue(String key) {
        return stringRedisTemplate.opsForValue().get(key);
    }

    /**
     * 写入字符串值并按小时设置过期时间。
     */
    @Override
    public void setValue(String key, String value, long ttlHours) {
        // （1）写入值后设置过期时间
        stringRedisTemplate.opsForValue().set(key, value);
        expire(key, ttlHours);
    }

    /**
     * 对指定 key 做自增并返回自增后的值。
     */
    @Override
    public Long increment(String key, long delta) {
        return stringRedisTemplate.opsForValue().increment(key, delta);
    }

    /**
     * 按小时设置 key 的过期时间。
     */
    @Override
    public void expire(String key, long ttlHours) {
        stringRedisTemplate.expire(key, ttlHours, TimeUnit.HOURS);
    }

    /**
     * 批量删除指定 key。
     */
    @Override
    public void deleteKeys(Collection<String> keys) {
        stringRedisTemplate.delete(keys);
    }
}

