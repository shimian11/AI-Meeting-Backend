package com.banma.interview.conversation.application;

import com.banma.interview.common.biz.message.MessageSequenceAllocator;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 消息序号服务：统一委托给 MessageSequenceAllocator 分配 AI 与智能体消息序号。
 */
@Service
@RequiredArgsConstructor
public class MessageSequenceService {

    private final MessageSequenceAllocator messageSequenceAllocator;

    /**
     * 分配指定 AI 会话的下一个消息序号。
     */
    public int nextAiMessageSeq(String sessionId) {
        return messageSequenceAllocator.nextAiMessageSeq(sessionId);
    }

    /**
     * 分配指定智能体会话的下一个消息序号。
     */
    public int nextAgentMessageSeq(String sessionId) {
        return messageSequenceAllocator.nextAgentMessageSeq(sessionId);
    }
}
