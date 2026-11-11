package com.banma.interview.conversation.application;

import com.banma.interview.agent.dao.entity.AgentMessage;
import com.banma.interview.ai.dao.entity.AiMessage;
import com.banma.interview.conversation.application.port.AgentMessagePersistencePort;
import com.banma.interview.conversation.application.port.AiMessagePersistencePort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Date;

/**
 * 会话消息落库服务：保存 AI 与智能体两侧的用户/助手消息，并返回分配到的消息序号。
 */
@Service
@RequiredArgsConstructor
public class ConversationMessagePersistenceService {

    private final MessageSequenceService messageSequenceService;
    private final AiMessagePersistencePort aiMessagePersistencePort;
    private final AgentMessagePersistencePort agentMessagePersistencePort;

    /**
     * 保存 AI 会话的用户消息，并返回其消息序号。
     */
    public int saveAiUserMessage(String sessionId, String content) {
        // （1）分配该会话的下一个消息序号
        int messageSeq = messageSequenceService.nextAiMessageSeq(sessionId);
        // （2）组装用户消息（messageType=1）并落库
        AiMessage message = new AiMessage();
        message.setSessionId(sessionId);
        message.setMessageType(1);
        message.setMessageContent(content);
        message.setMessageSeq(messageSeq);
        message.setCreateTime(new Date());
        message.setDelFlag(0);
        aiMessagePersistencePort.save(message);
        return messageSeq;
    }

    /**
     * 保存 AI 会话的助手消息（含推理内容、响应耗时与错误信息），并返回其消息序号。
     */
    public int saveAiAssistantMessage(
            String sessionId,
            String content,
            String reasoningContent,
            int responseTime,
            String errorMessage) {
        // （1）分配该会话的下一个消息序号
        int messageSeq = messageSequenceService.nextAiMessageSeq(sessionId);
        // （2）组装助手消息（messageType=2）并落库
        AiMessage message = new AiMessage();
        message.setSessionId(sessionId);
        message.setMessageType(2);
        message.setMessageContent(content);
        message.setReasoningContent(reasoningContent);
        message.setMessageSeq(messageSeq);
        message.setResponseTime(responseTime);
        message.setErrorMessage(errorMessage);
        message.setCreateTime(new Date());
        message.setDelFlag(0);
        aiMessagePersistencePort.save(message);
        return messageSeq;
    }

    /**
     * 保存智能体会话的用户消息，并返回其消息序号。
     */
    public int saveAgentUserMessage(String sessionId, String content) {
        // （1）分配该会话的下一个消息序号
        int messageSeq = messageSequenceService.nextAgentMessageSeq(sessionId);
        // （2）组装用户消息（messageType=1）并落库
        AgentMessage message = new AgentMessage();
        message.setSessionId(sessionId);
        message.setMessageType(1);
        message.setMessageContent(content);
        message.setMessageSeq(messageSeq);
        message.setCreateTime(new Date());
        message.setDelFlag(0);
        agentMessagePersistencePort.save(message);
        return messageSeq;
    }

    /**
     * 保存智能体会话的助手消息（含响应耗时与错误信息），并返回其消息序号。
     */
    public int saveAgentAssistantMessage(String sessionId, String content, int responseTime, String errorMessage) {
        // （1）分配该会话的下一个消息序号
        int messageSeq = messageSequenceService.nextAgentMessageSeq(sessionId);
        // （2）组装助手消息（messageType=2）并落库
        AgentMessage message = new AgentMessage();
        message.setSessionId(sessionId);
        message.setMessageType(2);
        message.setMessageContent(content);
        message.setMessageSeq(messageSeq);
        message.setResponseTime(responseTime);
        message.setErrorMessage(errorMessage);
        message.setCreateTime(new Date());
        message.setDelFlag(0);
        agentMessagePersistencePort.save(message);
        return messageSeq;
    }
}
