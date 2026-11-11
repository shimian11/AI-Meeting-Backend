package com.banma.interview.agent.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.banma.interview.agent.service.AgentTagService;
import com.banma.interview.agent.dao.entity.AgentTag;
import com.banma.interview.agent.dao.mapper.AgentTagMapper;
import org.springframework.stereotype.Service;

/**
 * 智能体标签服务实现，复用 MyBatis-Plus 的通用 CRUD 能力。
 */
@Service
public class AgentTagServiceImpl extends ServiceImpl<AgentTagMapper, AgentTag>
    implements AgentTagService {

}





