package com.banma.interview.common.ratelimit;

import cn.hutool.core.util.StrUtil;
import com.banma.interview.auth.application.CurrentUserService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Resolves a stable rate-limit key for each request.
 */
@Component
@RequiredArgsConstructor
public class RequestRateLimitKeyResolver {

    private static final String USER_PREFIX = "user:";
    private static final String IP_PREFIX = "ip:";

    private final CurrentUserService currentUserService;

    public String resolve(HttpServletRequest request) {
        String token = currentUserService.extractToken(request);
        if (StrUtil.isNotBlank(token)) {
            String username = currentUserService.getUsernameByToken(token);
            if (StrUtil.isNotBlank(username)) {
                return USER_PREFIX + username;
            }
        }
        return IP_PREFIX + resolveClientIp(request);
    }

    private String resolveClientIp(HttpServletRequest request) {
        String forwardedFor = trimToNull(request.getHeader("X-Forwarded-For"));
        if (forwardedFor != null) {
            int separator = forwardedFor.indexOf(',');
            return separator >= 0 ? forwardedFor.substring(0, separator).trim() : forwardedFor;
        }

        String realIp = trimToNull(request.getHeader("X-Real-IP"));
        if (realIp != null) {
            return realIp;
        }

        String remoteAddr = trimToNull(request.getRemoteAddr());
        return remoteAddr != null ? normalizeLoopback(remoteAddr) : "anonymous";
    }

    /**
     * 把 IPv6 回环地址归一化成 IPv4。
     *
     * <p>本机访问时 servlet 容器报的是 {@code 0:0:0:0:0:0:0:1}，
     * 直接拼进限流 key 会变成
     * {@code banma-interview:rate-limit:default:ip:0:0:0:0:0:0:0:1} 这种又长又难读的串。
     * 归一化后是 {@code ip:127.0.0.1}——语义等价（都是本机），可读性差很多。</p>
     */
    private String normalizeLoopback(String ip) {
        if ("0:0:0:0:0:0:0:1".equals(ip) || "::1".equals(ip)) {
            return "127.0.0.1";
        }
        return ip;
    }

    private String trimToNull(String value) {
        if (StrUtil.isBlank(value)) {
            return null;
        }
        String normalized = value.trim();
        return "unknown".equalsIgnoreCase(normalized) ? null : normalized;
    }
}
