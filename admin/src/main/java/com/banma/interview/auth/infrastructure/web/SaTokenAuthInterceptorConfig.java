package com.banma.interview.auth.infrastructure.web;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.router.SaRouter;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class SaTokenAuthInterceptorConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 注解校验拦截器：让 @SaCheckRole / @SaCheckLogin 生效。
        //    为什么必须显式注册：Sa-Token 的注解默认不扫描，不注册等于注解白写。
        //    sa-token 1.39 没有独立的 SaAnnotationInterceptor，SaInterceptor 无参构造即注解模式
        //    （字节码确认：无参构造会把 isAnnotation 置为 true）。
        registry.addInterceptor(new SaInterceptor()).addPathPatterns("/**");

        // 路由级登录校验：未在白名单内的 /api/banma/v1/** 必须已登录
        registry.addInterceptor(new SaInterceptor(handle -> SaRouter
                .match("/api/banma/v1/**")
                // actuator 探活放行：不在 /api/banma 前缀下，此行仅防御性声明（容器 healthcheck 依赖）
                .notMatch("/actuator/**")
                .notMatch("/api/banma/v1/users/login")
                .notMatch("/api/banma/v1/users/register")
                .notMatch("/api/banma/v1/users/has-username")
                .notMatch("/api/banma/v1/users/check-login")
                // 发验证码在注册之前，用户尚未登录，必须放行
                .notMatch("/api/banma/v1/users/mail-code")
                .check(StpUtil::checkLogin)
        )).addPathPatterns("/**");
    }
}
