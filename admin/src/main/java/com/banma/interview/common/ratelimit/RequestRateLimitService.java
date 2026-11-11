package com.banma.interview.common.ratelimit;

public interface RequestRateLimitService {

    boolean tryAcquire(String key, RequestRateLimitPolicy policy);
}
