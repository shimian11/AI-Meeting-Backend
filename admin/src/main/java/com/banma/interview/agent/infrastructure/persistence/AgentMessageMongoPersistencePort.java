package com.banma.interview.agent.infrastructure.persistence;

import com.banma.interview.agent.dao.entity.AgentMessage;
import com.banma.interview.agent.dao.repository.AgentMessageRepository;
import com.banma.interview.conversation.application.port.AgentMessagePersistencePort;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 智能体消息持久化端口实现：基于 MongoDB Repository 读写智能体会话消息。
 */
@Component
@RequiredArgsConstructor
public class AgentMessageMongoPersistencePort implements AgentMessagePersistencePort {

    private final AgentMessageRepository agentMessageRepository;

    /**
     * 查询指定会话未删除的智能体消息，按消息序号升序。
     */
    @Override
    public List<AgentMessage> findHistoryBySessionId(String sessionId) {
        return agentMessageRepository.findBySessionIdAndDelFlagOrderByMessageSeqAsc(sessionId, 0);
    }

    /**
     * 分页查询指定会话的智能体消息，按创建时间升序。
     */
    @Override
    public Page<AgentMessage> pageHistoryBySessionId(String sessionId, Pageable pageable) {
        return agentMessageRepository.findBySessionIdAndDelFlagOrderByCreateTimeAsc(sessionId, 0, pageable);
    }

    /**
     * 分页查询多个会话的智能体消息，按创建时间降序。
     */
    @Override
    public Page<AgentMessage> pageHistoryBySessionIds(List<String> sessionIds, Pageable pageable) {
        return agentMessageRepository.findBySessionIdInAndDelFlagOrderByCreateTimeDesc(sessionIds, 0, pageable);
    }

    /**
     * 分页查询全部未删除的智能体消息，按创建时间降序。
     */
    @Override
    public Page<AgentMessage> pageAllHistory(Pageable pageable) {
        return agentMessageRepository.findByDelFlagOrderByCreateTimeDesc(0, pageable);
    }

    /**
     * 保存单条智能体消息。
     */
    @Override
    public void save(AgentMessage message) {
        agentMessageRepository.save(message);
    }
}
