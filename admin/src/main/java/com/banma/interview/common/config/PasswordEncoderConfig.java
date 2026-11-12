package com.banma.interview.common.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 密码编码器配置。
 *
 * <p><b>为什么只引 spring-security-crypto 单包、不引 spring-boot-starter-security</b>：
 * 本项目的认证由 Sa-Token 独占（拦截器 + 编程式），一旦引入 Security starter，
 * 它会自动装配自己的过滤链接管所有请求（所有接口突然 401），与 Sa-Token 直接冲突。
 * 这里只需要 BCrypt 这一个纯工具类，与认证框架无关。</p>
 *
 * <p>BCrypt 默认强度 10（单次哈希约 100ms），登录无感，且自带随机盐——
 * 同一个密码两次哈希结果不同，无法用彩虹表反查。</p>
 */
@Configuration
public class PasswordEncoderConfig {

    /**
     * BCrypt 编码器：注册时 encode，登录时 matches 比对。
     *
     * <p>为什么是 Bean 而不是各处 new：便于单测替换（mock 掉慢速哈希），
     * 且编译器保证全局只有一种加密算法——若有人在别处 new 了别的编码器，
     * 存量密码就会解析失败。</p>
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
