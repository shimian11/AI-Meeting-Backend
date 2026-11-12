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
        registry.addInterceptor(new SaInterceptor(handle -> SaRouter
                .match("/api/banma/v1/**")
                // actuator 探活放行：不在 /api/banma 前缀下，此行仅防御性声明（容器 healthcheck 依赖）
                .notMatch("/actuator/**")
                .notMatch("/api/banma/v1/users/login")
                .notMatch("/api/banma/v1/users/register")
                .notMatch("/api/banma/v1/users/has-username")
                .notMatch(("/api/banma/v1/users/check-login"))
                .notMatch("/api/banma/v1/ai/doubao/**")
                .notMatch("/api/banma/v1/ai/roleplay/**")
                .notMatch("/api/banma/v1/users/mail-code")
                .check(StpUtil::checkLogin)
        )).addPathPatterns("/**");
    }
}
