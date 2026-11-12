package com.banma.interview.common.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 接口频控注解：按「调用者身份 + 接口」维度限次（Redis 原子计数）
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimit {
    /*窗口内允许的最大次数*/
    int limit();

    /*窗口时长（秒*/
    int windowSeconds();

    /*超限时抛给用户的提示语*/
    String message() default "请求太频繁，请稍后再试";
}
