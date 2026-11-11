package com.banma.interview.agent.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.banma.interview.agent.api.io.req.AgentConversationPageReqDTO;
import com.banma.interview.agent.api.io.resp.AgentConversationRespDTO;
import com.banma.interview.agent.api.io.resp.AgentSessionCreateRespDTO;
import com.banma.interview.agent.dao.entity.AgentConversation;
import com.banma.interview.agent.dao.repository.AgentConversationRepository;
import com.banma.interview.agent.service.AgentConversationService;
import com.banma.interview.auth.application.CurrentUserService;
import com.banma.interview.conversation.application.ConversationOwnershipService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.stream.Collectors;

/**
 * 智能体会话服务实现：负责会话创建、分页查询、更新与结束。
 */
@Service
@RequiredArgsConstructor
public class AgentConversationServiceImpl implements AgentConversationService {

    private final AgentConversationRepository agentConversationRepository;
    private final CurrentUserService currentUserService;
    private final ConversationOwnershipService conversationOwnershipService;

    /**
     * 为指定智能体创建会话，并返回新建会话的 sessionId。
     */
    @Override
    public String createConversation(String username, Long agentId, String firstMessage) {
        // （1）解析用户 id 并生成雪花 sessionId
        Long userId = getUserIdByUsername(username);
        String sessionId = IdUtil.getSnowflakeNextIdStr();

        // （2）组装会话字段（标题、计数、状态等）
        AgentConversation conversation = new AgentConversation();
        conversation.setSessionId(sessionId);
        conversation.setUserId(userId);
        conversation.setAgentId(agentId);
        conversation.setConversationTitle(generateTitle(firstMessage));
        conversation.setMessageCount(0);
        conversation.setTotalTokens(0);
        conversation.setStatus(1);
        conversation.setDelFlag(0);

        // （3）保存会话
        agentConversationRepository.save(conversation);
        return sessionId;
    }

    /**
     * 创建智能体会话，并返回 sessionId 与自动生成的会话标题。
     */
    @Override
    public AgentSessionCreateRespDTO createConversationWithTitle(String username, Long agentId, String firstMessage) {
        String sessionId = createConversation(username, agentId, firstMessage);
        String title = generateTitle(firstMessage);
        return new AgentSessionCreateRespDTO(sessionId, title);
    }

    /**
     * 分页查询当前用户的智能体会话，并转换为响应 DTO。
     */
    @Override
    public IPage<AgentConversationRespDTO> pageConversations(String username, AgentConversationPageReqDTO reqDTO) {
        // （1）解析用户 id 并构建分页参数
        Long userId = getUserIdByUsername(username);
        Pageable pageable = PageRequest.of(reqDTO.getCurrent() - 1, reqDTO.getSize());
        // （2）按关键字/状态条件查询会话
        org.springframework.data.domain.Page<AgentConversation> conversationPage = queryConversations(userId, reqDTO, pageable);

        // （3）转换为响应 DTO 并回填总数
        Page<AgentConversationRespDTO> resultPage = new Page<>(reqDTO.getCurrent(), reqDTO.getSize());
        resultPage.setTotal(conversationPage.getTotalElements());
        resultPage.setRecords(
                conversationPage.getContent().stream()
                        .map(conversation -> {
                            AgentConversationRespDTO respDTO = new AgentConversationRespDTO();
                            BeanUtils.copyProperties(conversation, respDTO);
                            return respDTO;
                        })
                        .collect(Collectors.toList())
        );
        return resultPage;
    }

    /**
     * 更新会话的消息数与 token 数，会话不存在时静默忽略。
     */
    @Override
    public void updateConversation(String sessionId, Integer messageCount, Integer totalTokens) {
        // 查询未删除会话后更新计数
        agentConversationRepository.findBySessionIdAndDelFlag(sessionId, 0)
                .ifPresent(conversation -> {
                    conversation.setMessageCount(messageCount);
                    conversation.setTotalTokens(totalTokens);
                    agentConversationRepository.save(conversation);
                });
    }

    /**
     * 结束会话：将会话状态置为已结束，会话不存在时静默忽略。
     */
    @Override
    public void endConversation(String sessionId) {
        agentConversationRepository.findBySessionIdAndDelFlag(sessionId, 0)
                .ifPresent(conversation -> {
                    conversation.setStatus(2);
                    agentConversationRepository.save(conversation);
                });
    }

    /**
     * 校验会话归属后结束会话。
     */
    @Override
    public void endConversation(String sessionId, Long userId) {
        AgentConversation conversation = conversationOwnershipService.requireOwnedConversation(sessionId, userId);
        conversation.setStatus(2);
        agentConversationRepository.save(conversation);
    }

    /**
     * 根据关键字与状态条件选择对应的会话查询方式。
     */
    private org.springframework.data.domain.Page<AgentConversation> queryConversations(
            Long userId,
            AgentConversationPageReqDTO reqDTO,
            Pageable pageable) {
        // （1）有关键字时走标题模糊查询
        if (StrUtil.isNotBlank(reqDTO.getKeyword())) {
            return queryByKeyword(userId, reqDTO, pageable);
        }
        // （2）仅有状态时按状态过滤
        if (reqDTO.getStatus() != null) {
            return agentConversationRepository.findByUserIdAndStatusAndDelFlagOrderByUpdateTimeDesc(
                    userId,
                    reqDTO.getStatus(),
                    0,
                    pageable
            );
        }
        return agentConversationRepository.findByUserIdAndDelFlagOrderByUpdateTimeDesc(userId, 0, pageable);
    }

    /**
     * 按标题关键字（可选叠加状态）查询会话。
     */
    private org.springframework.data.domain.Page<AgentConversation> queryByKeyword(
            Long userId,
            AgentConversationPageReqDTO reqDTO,
            Pageable pageable) {
        // （1）去除关键字首尾空白
        String keyword = reqDTO.getKeyword().trim();
        // （2）按是否指定状态选择查询条件
        if (reqDTO.getStatus() != null) {
            return agentConversationRepository.findByUserIdAndStatusAndDelFlagAndTitleContaining(
                    userId,
                    reqDTO.getStatus(),
                    0,
                    keyword,
                    pageable
            );
        }
        return agentConversationRepository.findByUserIdAndDelFlagAndTitleContaining(userId, 0, keyword, pageable);
    }

    /**
     * 根据首条消息生成会话标题：空则用默认标题，超过 20 个字符则截断并加省略号。
     */
    private String generateTitle(String firstMessage) {
        if (firstMessage == null || firstMessage.trim().isEmpty()) {
            return "New Conversation";
        }
        String title = firstMessage.trim();
        if (title.length() > 20) {
            title = title.substring(0, 20) + "...";
        }
        return title;
    }

    /**
     * 按用户名查询用户 id。
     */
    private Long getUserIdByUsername(String username) {
        return currentUserService.getUserIdByUsername(username);
    }
}
