package com.banma.interview.agent.api;


import com.banma.interview.common.convention.result.PageInfo;
import com.banma.interview.common.convention.result.Result;
import com.banma.interview.common.convention.result.Results;
import com.banma.interview.agent.api.io.req.AgentPropertiesReqDTO;
import com.banma.interview.agent.api.io.resp.AgentPropertiesRespDTO;
import com.banma.interview.agent.service.AgentPropertiesService;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

/**
 * agent配置管理层
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/api/banma/v1/agent-properties")
public class AgentPropertiesController {

    private final AgentPropertiesService agentPropertiesService;

    /**
     * 创建agent配置
     * @param requestParam
     * @return
     */
    @PostMapping
    public Result<Void> create(@RequestBody AgentPropertiesReqDTO requestParam) {
        agentPropertiesService.create(requestParam);
        return Results.success();
    }

    /**
     * 根据id删除agent配置
     * @param id
     * @return
     */
    @DeleteMapping("/{id}")
    public Result<Void> delete(@PathVariable("id") Long id) {
        agentPropertiesService.delete(id);
        return Results.success();
    }

    /**
     * 更新agent配置
     * @param requestParam
     * @return
     */
    @PutMapping
    public Result<Void> update(@RequestBody AgentPropertiesReqDTO requestParam) {
        agentPropertiesService.update(requestParam);
        return Results.success();
    }

    /**
     * 根据名称查询agent配置
     * @param name
     * @return
     */
    @GetMapping("/byName")
    public Result<AgentPropertiesRespDTO> getByName(@RequestParam("name") String name) {
        return Results.success(agentPropertiesService.getByName(name));
    }

    /**
     * 分页查询agent配置
     * @param requestParam
     * @return
     */
    @GetMapping
    public Result<PageInfo<AgentPropertiesRespDTO>> getByPage(AgentPropertiesReqDTO requestParam) {
        return Results.success(agentPropertiesService.getByPage(requestParam));
    }
}