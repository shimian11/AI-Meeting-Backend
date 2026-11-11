package com.banma.interview.conversation.application;

import cn.hutool.core.util.StrUtil;
import com.banma.interview.agent.dao.entity.AgentConversation;
import com.banma.interview.agent.dao.repository.AgentConversationRepository;
import com.banma.interview.common.convention.exception.ClientException;
import com.banma.interview.common.enums.InterviewErrorCodeEnum;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Centralized conversation ownership checks shared across domains.
 * 集中校验会话归属：供各领域复用的会话所有权检查。
 */
@Component
@RequiredArgsConstructor
public class ConversationOwnershipService {

    private final AgentConversationRepository agentConversationRepository;

    /**
     * 校验并返回属于指定用户的会话；参数缺失、会话不存在或越权时抛出异常。
     */
    public AgentConversation requireOwnedConversation(String sessionId, Long userId) {
        // （1）校验 sessionId 与 userId
        if (StrUtil.isBlank(sessionId)) {
            throw new ClientException(InterviewErrorCodeEnum.SESSION_ID_EMPTY);
        }
        validateUserId(userId);

        // （2）查询未删除会话并校验存在
        Optional<AgentConversation> conversationOpt = agentConversationRepository.findBySessionIdAndDelFlag(sessionId, 0);
        if (conversationOpt.isEmpty()) {
            throw new ClientException(InterviewErrorCodeEnum.CONVERSATION_NOT_FOUND);
        }
        AgentConversation conversation = conversationOpt.get();
        // （3）校验会话归属当前用户
        if (!userId.equals(conversation.getUserId())) {
            throw new ClientException(InterviewErrorCodeEnum.CONVERSATION_ACCESS_DENIED);
        }
        return conversation;
    }

    /**
     * 查询指定用户拥有的所有未删除会话 id（去重）。
     */
    public List<String> listOwnedSessionIds(Long userId) {
        // （1）校验 userId 合法
        validateUserId(userId);
        // （2）查询会话并提取去重后的 sessionId
        List<AgentConversation> conversations = agentConversationRepository.findByUserIdAndDelFlag(userId, 0);
        if (conversations == null || conversations.isEmpty()) {
            return Collections.emptyList();
        }
        return conversations.stream()
                .map(AgentConversation::getSessionId)
                .filter(StrUtil::isNotBlank)
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * 校验 userId 合法：非空且大于 0，否则抛出参数异常。
     */
    private void validateUserId(Long userId) {
        // userId 为空或非正数时抛出异常
        if (userId == null || userId <= 0) {
            throw new ClientException(InterviewErrorCodeEnum.INVALID_USER_ID);
        }
    }
}
