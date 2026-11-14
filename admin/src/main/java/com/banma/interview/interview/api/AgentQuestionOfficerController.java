package com.banma.interview.interview.api;


import com.banma.interview.common.convention.result.Result;
import com.banma.interview.common.convention.result.Results;
import com.banma.interview.interview.infrastructure.client.BanmaAgentClient;
import com.banma.interview.interview.infrastructure.client.dto.QuestionOfficerReqDTO;
import com.banma.interview.interview.infrastructure.client.dto.QuestionOfficerRespDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * 出题官网关：前端 → Java → Python。
 * 只做三件事：补齐 traceId、透传请求、回传响应。
 * 真正的意图路由/简历解析/RAG/合成都在 Python，Java 不重复实现。
 */
@RestController
@RequiredArgsConstructor
@Slf4j
@RequestMapping("/api/banma/v1/interview/agent")
public class AgentQuestionOfficerController {

    private final BanmaAgentClient  banmaAgentClient;

    @PostMapping("/question-officer")
    public Result<QuestionOfficerRespDTO> runQuesionOfficer(@RequestBody QuestionOfficerReqDTO requestParam) {
        requestParam.setTraceId(UUID.randomUUID().toString());
        return Results.success(banmaAgentClient.runQuestionOfficer(requestParam));
    }
}
