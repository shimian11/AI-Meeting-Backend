package com.banma.interview.interview.api;


import com.banma.interview.common.convention.annotation.CurrentUser;
import com.banma.interview.common.convention.context.UserContext;
import com.banma.interview.common.convention.result.Result;
import com.banma.interview.common.convention.result.Results;
import com.banma.interview.interview.application.InterviewWorkflowService;
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
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/banma/v1/interview/agent")
public class AgentQuestionOfficerController {

    /** 面试流程服务：出题转发 + 结果落库 */
    private final InterviewWorkflowService interviewWorkflowService;

    /**
     * 出题入口
     */

    @PostMapping("/question-officer")
    public Result<QuestionOfficerRespDTO> runQuestionOfficer(
            @RequestBody QuestionOfficerReqDTO requestParam,
            @CurrentUser UserContext currentUser){
        return Results.success(interviewWorkflowService.runQuestionOfficer(requestParam, currentUser.getUsername()));
    }

}
