package com.banma.interview.agent.application;

import cn.hutool.core.util.StrUtil;
import com.banma.interview.agent.dao.entity.AgentConversation;
import com.banma.interview.agent.dao.entity.AgentPropertiesDO;
import com.banma.interview.agent.dao.repository.AgentConversationRepository;
import com.banma.interview.toolkit.xunfei.AgentPropertiesLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 智能体解析器：会话已绑定智能体时以绑定值为准，否则使用请求指定的智能体。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentResolver {

    private final AgentConversationRepository agentConversationRepository;
    private final AgentPropertiesLoader agentPropertiesLoader;

    /**
     * 解析会话实际使用的智能体 id：会话绑定优先，其次使用请求指定的 id。
     */
    public Long resolveAgentId(String sessionId, Long requestedAgentId) {
        // （1）查询会话绑定的智能体 id
        Long boundAgentId = findBoundAgentId(sessionId);
        if (boundAgentId != null) {
            // （2）请求值与绑定值冲突时记录告警，并以绑定值为准
            if (requestedAgentId != null && !requestedAgentId.equals(boundAgentId)) {
                log.warn(
                        "Requested agentId {} overridden by session-bound agentId {}, sessionId={}",
                        requestedAgentId,
                        boundAgentId,
                        sessionId
                );
            }
            return boundAgentId;
        }
        // （3）无绑定时返回请求指定的智能体 id
        return requestedAgentId;
    }

    /**
     * 解析出智能体 id 后加载其配置，未解析到 id 时返回 null。
     */
    public AgentPropertiesDO resolveAgent(String sessionId, Long requestedAgentId) {
        // （1）解析智能体 id
        Long resolvedAgentId = resolveAgentId(sessionId, requestedAgentId);
        // （2）id 为空直接返回 null，否则加载配置
        if (resolvedAgentId == null) {
            return null;
        }
        return agentPropertiesLoader.getByAgentId(resolvedAgentId);
    }

    /**
     * 查询会话绑定的智能体 id，sessionId 为空或无对应会话时返回 null。
     */
    public Long findBoundAgentId(String sessionId) {
        // （1）sessionId 为空直接返回 null
        if (StrUtil.isBlank(sessionId)) {
            return null;
        }
        // （2）查询未删除会话并取其 agentId
        return agentConversationRepository.findBySessionIdAndDelFlag(sessionId, 0)
                .map(AgentConversation::getAgentId)
                .orElse(null);
    }
}
