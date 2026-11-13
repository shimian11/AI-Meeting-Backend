package com.banma.interview.interview.infrastructure.client;

import io.netty.channel.ChannelOption;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;

import java.time.Duration;

/**
 * Python Agent 的 WebClient 装配。
 *
 * 适用场景：WebClient 适合高并发、流式传输（如 SSE）、响应式链路；
 * RestClient 适合传统的同步请求-响应场景。
 * WebClient 是响应式、非阻塞的；而 RestClient 是同步、阻塞的
 */
@Configuration
@EnableConfigurationProperties(AgentClientProperties.class)//创建AgentClientProperties这个bean
public class AgentClientConfiguration {

    @Bean("banmaAgentWebClient")
    public WebClient banmaAgentWebClient(AgentClientProperties properties) {
        // （1）连接池：闲置 30s 回收，避免 Python 侧重启后拿到死连接
        ConnectionProvider provider = ConnectionProvider.builder("banma-agent")
                .maxConnections(properties.getMaxConnections())
                .pendingAcquireMaxCount(properties.getPendingAcquireMaxCount())
                .pendingAcquireTimeout(Duration.ofSeconds(5))
                .maxIdleTime(Duration.ofSeconds(30))
                .build();

        // （2）超时：建连与响应分开设；响应超时是兜底，真正按 stage 的超时在 ai-guard 里
        HttpClient httpClient = HttpClient.create(provider)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, properties.getConnectTimeoutMillis())
                .responseTimeout(Duration.ofMillis(properties.getResponseTimeoutMillis()));

        return WebClient.builder()
                .baseUrl(properties.getBaseUrl())
                .clientConnector(new ReactorClientHttpConnector(httpClient))
                // （3）令牌在此注入：前端不持有，DB 不存（ADR-017）
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + properties.getToken())
                .codecs(codec -> codec.defaultCodecs()
                        .maxInMemorySize(properties.getMaxInMemorySizeKb() * 1024))
                .build();
    }
}
