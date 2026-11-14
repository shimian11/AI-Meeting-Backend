package com.banma.interview.interview.infrastructure.client;

import com.banma.interview.common.convention.exception.ClientException;
import com.banma.interview.interview.infrastructure.client.dto.QuestionOfficerReqDTO;
import com.banma.interview.interview.infrastructure.client.dto.QuestionOfficerRespDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatusCode;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;

/**
 * Python Agent 客户端：Java 侧唯一的外部 AI 出口（ADR-017 单路由）。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class BanmaAgentClient {

    /** 与 AgentClientConfiguration 里的 Bean 名一致 */
    private final WebClient banmaAgentWebClient;

    /**
     * 调用出题官（同步阻塞）
     * 阻塞是有意的：上游 InterviewAiInvoker 的 Callable 链本就是同步模型，
     * 用 block() 保持同一执行模型，避免在同步链路里混入响应式传播（半个响应式最难维护）。
     */
    public QuestionOfficerRespDTO runQuestionOfficer(QuestionOfficerReqDTO request) {
        long start = System.currentTimeMillis();
        try {
            QuestionOfficerRespDTO response = banmaAgentWebClient.post()
                    .uri("/v1/agents/question-officer/run")
                    .bodyValue(request)
                    .retrieve()
                    .onStatus(HttpStatusCode::isError, this::toException)
                    .bodyToMono(QuestionOfficerRespDTO.class)
                    .block(Duration.ofSeconds(70));

            log.info("BanmaAgent question-officer ok, traceId={}, intent={}, source={}, cost={}ms",
                    request.getTraceId(),
                    response == null ? null : response.getIntent(),
                    response == null ? null : response.getQuestionsSource(),
                    System.currentTimeMillis() - start);
            return response;
        } catch (ClientException ex) {
            throw ex;
        } catch (Exception ex) {
            // 连接拒绝 / 读超时 / 反序列化失败统一收敛，避免上游拿到裸异常
            log.error("BanmaAgent question-officer failed, traceId={}, cost={}ms",
                    request.getTraceId(), System.currentTimeMillis() - start, ex);
            throw new ClientException("AI 服务调用失败：" + rootMessage(ex));
        }
    }

    private Mono<Throwable> toException(ClientResponse response) {
        int status = response.statusCode().value();
        String prefix = (status == 401 || status == 403)
                ? "Agent 内部鉴权失败，请检查 BANMA_AGENT_TOKEN 是否与 Python 侧一致"
                : "Agent 服务异常（HTTP " + status + "）";
        return response.bodyToMono(String.class)
                .defaultIfEmpty("")
                .map(body -> new ClientException(prefix + (body.isEmpty() ? "" : "：" + body)));
    }

    private String rootMessage(Throwable ex) {
        Throwable cur = ex;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        return msg == null ? cur.getClass().getSimpleName() : msg;
    }
}
