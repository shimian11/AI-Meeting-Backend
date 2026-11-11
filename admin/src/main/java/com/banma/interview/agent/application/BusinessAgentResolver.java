package com.banma.interview.agent.application;

import cn.hutool.core.util.StrUtil;
import com.banma.interview.agent.dao.entity.AgentPropertiesDO;
import com.banma.interview.common.convention.exception.ClientException;
import com.banma.interview.common.enums.InterviewErrorCodeEnum;
import com.banma.interview.toolkit.xunfei.AgentPropertiesLoader;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 业务场景智能体解析器：按配置名称与场景候选名称依次匹配可用的智能体。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BusinessAgentResolver {

    private final BusinessAgentBindingProperties bindingProperties;
    private final AgentPropertiesLoader agentPropertiesLoader;

    /**
     * 解析指定业务场景所需的智能体配置，找不到时抛出业务异常。
     */
    public AgentPropertiesDO resolveRequired(BusinessAgentScene scene) {
        // （1）收集候选智能体名称：配置名优先，再追加场景候选名
        Set<String> candidateAgentNames = new LinkedHashSet<>();
        String configuredAgentName = bindingProperties.resolveAgentName(scene);             //获取配置的智能体名称
        if (StrUtil.isNotBlank(configuredAgentName)) {
            candidateAgentNames.add(configuredAgentName.trim());
        }
        candidateAgentNames.addAll(scene.getCandidateAgentNames());

        // （2）按顺序查找第一条存在的配置（配置名未命中时记录降级告警）
        for (String candidateAgentName : candidateAgentNames) {
            AgentPropertiesDO agentProperties = agentPropertiesLoader.getByAgentName(candidateAgentName);
            if (agentProperties != null) {
                if (StrUtil.isNotBlank(configuredAgentName) && !configuredAgentName.trim().equals(candidateAgentName)) {
                    log.warn(
                            "Configured agent not found, fallback matched scene={}, configuredName={}, matchedName={}, agentId={}",
                            scene.getCode(),
                            configuredAgentName,
                            candidateAgentName,
                            agentProperties.getId()
                    );
                } else {
                    log.info("Resolved business agent scene={}, agentName={}, agentId={}",
                            scene.getCode(), candidateAgentName, agentProperties.getId());
                }
                return agentProperties;
            }
        }

        // （3）均未匹配时记录日志并抛业务异常
        log.error("No agent configuration found for scene={}, candidateNames={}", scene.getCode(), candidateAgentNames);
        throw new ClientException(
                "agent binding not found for scene=" + scene.getCode() + ", candidateNames=" + candidateAgentNames,
                InterviewErrorCodeEnum.AGENT_CONFIG_NOT_FOUND
        );
    }
}
