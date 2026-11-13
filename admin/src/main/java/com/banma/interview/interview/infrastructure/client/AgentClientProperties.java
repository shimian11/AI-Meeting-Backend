package com.banma.interview.interview.infrastructure.client;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Python Agent 客户端配置。
 */
@Data
@Validated
@ConfigurationProperties(prefix = "banma-interview.agent-client")
public class AgentClientProperties {

    /** Python Agent 基址，必填 */
    @NotBlank(message = "agent 地址未配置")
    private String baseUrl;

    /**
     * 内部令牌（Python 侧 require_token 校验）。
     *
     * 不强制非空：本地未接 Python 时也应能起服务；为空时所有 AI 调用会返回明确的鉴权提示，
     * 而不是一个看不出原因的 500。
     */
    private String token;

    /** 建连超时：内网 2 秒足够；连不上应快速失败，而不是拖住线程 */
    @Min(value = 1, message = "connect-timeout-millis 必须大于 0")
    private int connectTimeoutMillis;

    /**
     * 兜底总超时。
     *
     * 取值须大于 {@code banma-interview.ai-guard.stages} 中最长的 stage 超时（出题 60s）——
     * 否则守卫还没超时客户端先掐断，异常类型会从「业务超时」变成「网络超时」，排查时误导人。
     */
    @Min(value = 1, message = "response-timeout-millis 必须大于 0")
    private int responseTimeoutMillis;

    /** 连接池上限：Python 是单进程 uvicorn，开太多只会在服务端排队 */
    @Min(value = 1, message = "max-connections 必须大于 0")
    private int maxConnections;

    /** 等待可用连接的最大排队数，超出即快速失败 */
    @Min(value = 1, message = "pending-acquire-max-count 必须大于 0")
    private int pendingAcquireMaxCount;

    /** 响应体内存上限（KB）：出题响应含 questions 与 steps_trace，4MB 余量充足 */
    @Min(value = 1, message = "max-in-memory-size-kb 必须大于 0")
    private int maxInMemorySizeKb;
}
