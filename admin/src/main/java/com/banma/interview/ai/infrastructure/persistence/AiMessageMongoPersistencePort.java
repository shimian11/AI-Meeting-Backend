package com.banma.interview.ai.infrastructure.persistence;

import com.banma.interview.ai.dao.entity.AiMessage;
import com.banma.interview.ai.dao.repository.AiMessageRepository;
import com.banma.interview.conversation.application.port.AiMessagePersistencePort;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * AI 消息持久化端口实现：基于 MongoDB Repository 读写 AI 会话消息。
 */
@Component
@RequiredArgsConstructor
public class AiMessageMongoPersistencePort implements AiMessagePersistencePort {

    private final AiMessageRepository aiMessageRepository;

    /**
     * 查询指定会话未删除的 AI 消息，按消息序号升序。
     */
    @Override
    public List<AiMessage> findHistoryBySessionId(String sessionId) {
        return aiMessageRepository.findBySessionIdAndDelFlagOrderByMessageSeqAsc(sessionId, 0);
    }

    /**
     * 分页查询指定会话的 AI 消息，按创建时间升序。
     */
    @Override
    public Page<AiMessage> pageHistoryBySessionId(String sessionId, Pageable pageable) {
        return aiMessageRepository.findBySessionIdAndDelFlagOrderByCreateTimeAsc(sessionId, 0, pageable);
    }

    /**
     * 分页查询多个会话的 AI 消息，按创建时间降序。
     */
    @Override
    public Page<AiMessage> pageHistoryBySessionIds(List<String> sessionIds, Pageable pageable) {
        return aiMessageRepository.findBySessionIdInAndDelFlagOrderByCreateTimeDesc(sessionIds, 0, pageable);
    }

    /**
     * 分页查询全部未删除的 AI 消息，按创建时间降序。
     */
    @Override
    public Page<AiMessage> pageAllHistory(Pageable pageable) {
        return aiMessageRepository.findByDelFlagOrderByCreateTimeDesc(0, pageable);
    }

    /**
     * 保存单条 AI 消息。
     */
    @Override
    public void save(AiMessage message) {
        aiMessageRepository.save(message);
    }
}
