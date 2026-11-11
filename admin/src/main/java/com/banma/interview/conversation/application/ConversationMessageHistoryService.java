package com.banma.interview.conversation.application;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.banma.interview.agent.api.io.resp.AgentMessageHistoryRespDTO;
import com.banma.interview.ai.api.io.BaseMessageHistoryRespDTO;
import com.banma.interview.ai.api.io.resp.AiMessageHistoryRespDTO;
import com.banma.interview.conversation.application.port.AgentMessagePersistencePort;
import com.banma.interview.conversation.application.port.AiMessagePersistencePort;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Supplier;

/**
 * 会话消息历史查询服务：统一 AI 与智能体两侧消息的历史查询与分页转换。
 */
@Service
@RequiredArgsConstructor
public class ConversationMessageHistoryService {

    private final AiMessagePersistencePort aiMessagePersistencePort;
    private final AgentMessagePersistencePort agentMessagePersistencePort;

    /**
     * 查询指定会话的 AI 历史消息，并转换为响应 DTO 列表。
     */
    public List<AiMessageHistoryRespDTO> listAiHistory(String sessionId) {
        return mapList(aiMessagePersistencePort.findHistoryBySessionId(sessionId), AiMessageHistoryRespDTO::new);
    }

    /**
     * 查询指定会话的智能体历史消息，并转换为响应 DTO 列表。
     */
    public List<AgentMessageHistoryRespDTO> listAgentHistory(String sessionId) {
        return mapList(agentMessagePersistencePort.findHistoryBySessionId(sessionId), AgentMessageHistoryRespDTO::new);
    }

    /**
     * 分页查询指定会话的 AI 历史消息。
     */
    public IPage<AiMessageHistoryRespDTO> pageAiHistory(String sessionId, Integer current, Integer size) {
        Pageable pageable = PageRequest.of(current - 1, size);
        return mapPage(aiMessagePersistencePort.pageHistoryBySessionId(sessionId, pageable), current, size,
                AiMessageHistoryRespDTO::new);
    }

    /**
     * 分页查询多个会话的 AI 历史消息。
     */
    public IPage<AiMessageHistoryRespDTO> pageAiHistory(List<String> sessionIds, Integer current, Integer size) {
        Pageable pageable = PageRequest.of(current - 1, size);
        return mapPage(aiMessagePersistencePort.pageHistoryBySessionIds(sessionIds, pageable), current, size,
                AiMessageHistoryRespDTO::new);
    }

    /**
     * 分页查询全部 AI 历史消息。
     */
    public IPage<AiMessageHistoryRespDTO> pageAllAiHistory(Integer current, Integer size) {
        Pageable pageable = PageRequest.of(current - 1, size);
        return mapPage(aiMessagePersistencePort.pageAllHistory(pageable), current, size, AiMessageHistoryRespDTO::new);
    }

    /**
     * 分页查询指定会话的智能体历史消息。
     */
    public IPage<AgentMessageHistoryRespDTO> pageAgentHistory(String sessionId, Integer current, Integer size) {
        Pageable pageable = PageRequest.of(current - 1, size);
        return mapPage(agentMessagePersistencePort.pageHistoryBySessionId(sessionId, pageable), current, size,
                AgentMessageHistoryRespDTO::new);
    }

    /**
     * 分页查询多个会话的智能体历史消息。
     */
    public IPage<AgentMessageHistoryRespDTO> pageAgentHistory(List<String> sessionIds, Integer current, Integer size) {
        Pageable pageable = PageRequest.of(current - 1, size);
        return mapPage(agentMessagePersistencePort.pageHistoryBySessionIds(sessionIds, pageable), current, size,
                AgentMessageHistoryRespDTO::new);
    }

    /**
     * 分页查询全部智能体历史消息。
     */
    public IPage<AgentMessageHistoryRespDTO> pageAllAgentHistory(Integer current, Integer size) {
        Pageable pageable = PageRequest.of(current - 1, size);
        return mapPage(agentMessagePersistencePort.pageAllHistory(pageable), current, size,
                AgentMessageHistoryRespDTO::new);
    }

    /**
     * 将源记录列表复制转换为目标 DTO 列表。
     */
    private <S, T extends BaseMessageHistoryRespDTO> List<T> mapList(List<S> source, Supplier<T> targetSupplier) {
        List<T> result = new ArrayList<>();
        // （1）源为空时直接返回空列表
        if (source == null || source.isEmpty()) {
            return result;
        }
        // （2）逐条复制属性到新建 DTO
        for (S item : source) {
            T target = targetSupplier.get();
            BeanUtils.copyProperties(item, target);
            result.add(target);
        }
        return result;
    }

    /**
     * 将 Spring Data 分页结果转换为 MyBatis-Plus 分页对象。
     */
    private <S, T extends BaseMessageHistoryRespDTO> IPage<T> mapPage(
            org.springframework.data.domain.Page<S> source,
            Integer current,
            Integer size,
            Supplier<T> targetSupplier) {
        // （1）创建目标分页并回填总数
        Page<T> target = new Page<>(current, size);
        target.setTotal(source.getTotalElements());
        // （2）转换并设置记录列表
        target.setRecords(mapList(source.getContent(), targetSupplier));
        return target;
    }
}
