package com.banma.interview.common.aspect;

import com.banma.interview.common.annotation.RateLimit;
import com.banma.interview.common.convention.exception.ClientException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.lang.reflect.Field;
import java.time.Duration;

/**
 * 频控切面: 做次数限制
 *
 * 为什么用 INCR 而不是先 GET 再 SET：并发下 GET/SET 会穿透（两个请求都读到 9，都写成 10），
 * INCR 是 Redis 单线程原子操作，天然不穿透。
 */
@Slf4j
@Aspect
@Component
@RequiredArgsConstructor
public class RateLimitAspect {

    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 环绕通知：计数 → 判断 → 放行或拒绝。
     * 解析调用者身份（发码场景是邮箱，已登录场景是 username）
     * Redis INCR 原子累加；首次累加时设 TTL = 窗口时长（Redis 自动过期，无需代码清理）
     * 超过上限抛业务异常，未超限则放行原方法
     */
    @Around("@annotation(rateLimit)")
    public Object around(ProceedingJoinPoint joinPoint, RateLimit rateLimit) throws Throwable {
        // 1、身份+方法名组成唯一key
        String identity = resolveIdentity(joinPoint);
        String method = ((MethodSignature) joinPoint.getSignature()).getMethod().getName();
        String key = "rate:" + method + ":" + identity;

        // 2、原子计数；首次（count == 1）时设 TTL = 窗口时长
        //    为什么必须在这一刻设：只有第一次写入时 key 才刚被创建；
        //    若错过，key 会永不过期（TTL = -1），计数只增不减，配额变成"终身一次上限"
        Long count = stringRedisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            stringRedisTemplate.expire(key, Duration.ofSeconds(rateLimit.windowSeconds()));
        }

        // 3、超过上限才拦（注意是 > 不是 ==，且拦完不 reset —— 计数继续累计，
        //    这样用户在窗口内反复重试也仍是"已超限"，不会因为被拦一次就白捡一次额度）
        if (count != null && count > rateLimit.limit()) {
            log.warn("RateLimit 触发拦截: key={}, count={}, limit={}", key, count, rateLimit.limit());
            throw new ClientException(rateLimit.message());
        }

        return joinPoint.proceed();
    }

    /**
     * 解析调用者身份。
     * 步骤：
     * 遍历方法参数，找带 email 字段的对象（发码接口未登录，只能用邮箱识别）
     * 找不到则尝试 username 字段
     * 都没有则记为 anonymous（兜底，避免切面自身报错影响主流程）
     */
    private String resolveIdentity(ProceedingJoinPoint joinPoint) {
        for (Object arg : joinPoint.getArgs()) {
            if(arg == null){
                continue;
            }
            //1、找email
            String email = readStringField(arg, "email");
            if(email != null){
                return email;
            }
            //2、再找username
            String username = readStringField(arg, "username");
            if(username != null){
                return username;
            }
        }
        //3、兜底
        return "anonymous";
    }

    /**
     * 反射读取对象的字符串字段（读不到或无值返回 null）。
     * 为什么用反射而不是直接 import 业务 DTO：切面属于 common 层，
     * 依赖 user 层的 DTO 会造成反向依赖（common → user），破坏分层。
     */
    private String readStringField(Object target, String fieldName){
        try {
            Field field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            Object value = field.get(target);
            if(value instanceof String text && !text.isBlank()){
                return text;
            }
            return null;
        } catch (NoSuchFieldException | IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }
}
