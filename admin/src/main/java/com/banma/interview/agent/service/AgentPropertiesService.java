package com.banma.interview.agent.service;

import com.banma.interview.common.convention.result.PageInfo;
import com.banma.interview.agent.dao.entity.AgentPropertiesDO;
import com.baomidou.mybatisplus.extension.service.IService;
import com.banma.interview.agent.api.io.req.AgentPropertiesReqDTO;
import com.banma.interview.agent.api.io.resp.AgentPropertiesRespDTO;

import java.util.List;

public interface AgentPropertiesService extends IService<AgentPropertiesDO> {
    /**
     * 创建智能体配置
     */
    void create(AgentPropertiesReqDTO requestParam);

    /**
     * 删除智能体配置
     */
    void delete(Long id);

    /**
     * 更新智能体配置
     */
    void update(AgentPropertiesReqDTO requestParam);

    /**
     * 根据名称查询智能体配置
     */
    AgentPropertiesRespDTO getByName(String name);

    /**
     * 分页查询智能体配置
     */
    PageInfo<AgentPropertiesRespDTO> getByPage(AgentPropertiesReqDTO requestParam);

    /**
     * 查询前10个智能体配置
     */
    List<AgentPropertiesDO> listTop10();

    List<AgentPropertiesDO> listActiveAgents();
}

