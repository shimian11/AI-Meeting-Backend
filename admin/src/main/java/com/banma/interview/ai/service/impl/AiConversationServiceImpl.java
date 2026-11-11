package com.banma.interview.ai.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.banma.interview.ai.api.io.req.AiConversationPageReqDTO;
import com.banma.interview.ai.api.io.resp.AiConversationRespDTO;
import com.banma.interview.ai.api.io.resp.AiSessionCreateRespDTO;
import com.banma.interview.ai.dao.entity.AiConversation;
import com.banma.interview.ai.dao.entity.AiPropertiesDO;
import com.banma.interview.ai.dao.repository.AiConversationRepository;
import com.banma.interview.ai.service.AiConversationService;
import com.banma.interview.ai.service.AiPropertiesService;
import com.banma.interview.common.convention.exception.ClientException;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * AI 会话服务实现：负责会话创建、分页查询、更新、结束、删除与归属校验。
 */
@Service
@RequiredArgsConstructor
public class AiConversationServiceImpl implements AiConversationService {

    private final AiConversationRepository aiConversationRepository;
    private final AiPropertiesService aiPropertiesService;

    /**
     * 创建 AI 会话：校验 AI 配置后生成 sessionId，并初始化会话记录。
     *
     * @return 新建会话的 sessionId
     */
    @Override
    public String createConversation(String username, Long aiId, String firstMessage) {
        // （1）校验 AI 配置存在、未删除且已启用
        AiPropertiesDO aiProperties = aiPropertiesService.getById(aiId);
        if (aiProperties == null || aiProperties.getDelFlag() == 1 || aiProperties.getIsEnabled() == 0) {
            throw new ClientException("AI config does not exist or is disabled");
        }

        // （2）生成雪花 sessionId，并根据首条消息生成标题
        String sessionId = IdUtil.getSnowflakeNextIdStr();
        String title = generateTitle(firstMessage);

        // （3）组装会话字段并保存
        AiConversation conversation = new AiConversation();
        conversation.setSessionId(sessionId);
        conversation.setUsername(username);
        conversation.setAiId(aiId);
        conversation.setTitle(title);
        conversation.setStatus(1);
        conversation.setMessageCount(0);
        conversation.setLastMessageTime(new Date());
        conversation.setCreateTime(new Date());
        conversation.setUpdateTime(new Date());
        conversation.setDelFlag(0);

        aiConversationRepository.save(conversation);
        return sessionId;
    }

    /**
     * 创建 AI 会话，并返回 sessionId 与自动生成的会话标题。
     */
    @Override
    public AiSessionCreateRespDTO createConversationWithTitle(String username, Long aiId, String firstMessage) {
        // （1）复用创建逻辑得到 sessionId
        String sessionId = createConversation(username, aiId, firstMessage);
        // （2）组装返回体（sessionId + 标题）
        AiSessionCreateRespDTO respDTO = new AiSessionCreateRespDTO();
        respDTO.setSessionId(sessionId);
        respDTO.setConversationTitle(generateTitle(firstMessage));
        return respDTO;
    }

    /**
     * 分页查询当前用户的会话，并按需回填关联 AI 的名称。
     */
    @Override
    public IPage<AiConversationRespDTO> pageConversations(String username, AiConversationPageReqDTO requestParam) {
        // （1）构建分页参数（Spring Data 页码从 0 开始）
        Pageable pageable = PageRequest.of(requestParam.getCurrent() - 1, requestParam.getSize());
        org.springframework.data.domain.Page<AiConversation> conversationPage;

        // （2）按是否指定 aiId 选择查询条件
        if (requestParam.getAiId() != null) {
            conversationPage = aiConversationRepository
                    .findByUsernameAndAiIdAndDelFlagOrderByCreateTimeDesc(username, requestParam.getAiId(), 0, pageable);
        } else {
            conversationPage = aiConversationRepository
                    .findByUsernameAndDelFlagOrderByCreateTimeDesc(username, 0, pageable);
        }

        // （3）转换实体为 DTO，并回填 AI 名称
        Page<AiConversationRespDTO> resultPage = new Page<>(requestParam.getCurrent(), requestParam.getSize());
        resultPage.setTotal(conversationPage.getTotalElements());

        List<AiConversationRespDTO> records = conversationPage.getContent().stream()
                .map(conversation -> {
                    AiConversationRespDTO respDTO = new AiConversationRespDTO();
                    BeanUtil.copyProperties(conversation, respDTO);
                    AiPropertiesDO aiProperties = aiPropertiesService.getById(conversation.getAiId());
                    if (aiProperties != null) {
                        respDTO.setAiName(aiProperties.getAiName());
                    }
                    return respDTO;
                })
                .collect(Collectors.toList());

        resultPage.setRecords(records);
        return resultPage;
    }

    /**
     * 更新会话的消息数与标题（均可选），并刷新最后消息时间。
     */
    @Override
    public void updateConversation(String sessionId, Integer messageSeq, String title) {
        // （1）查询未删除会话，不存在则直接返回
        Optional<AiConversation> conversationOpt = aiConversationRepository.findBySessionIdAndDelFlag(sessionId, 0);
        if (conversationOpt.isEmpty()) {
            return;
        }
        AiConversation conversation = conversationOpt.get();
        // （2）按需更新消息数
        if (messageSeq != null) {
            conversation.setMessageCount(messageSeq);
        }
        // （3）标题非空时更新标题
        if (StrUtil.isNotBlank(title)) {
            conversation.setTitle(title);
        }
        // （4）刷新最后消息时间与更新时间并保存
        conversation.setLastMessageTime(new Date());
        conversation.setUpdateTime(new Date());
        aiConversationRepository.save(conversation);
    }

    /**
     * 校验会话归属后更新会话信息。
     */
    @Override
    public void updateConversation(String sessionId, Integer messageSeq, String title, String username) {
        requireOwnedConversation(sessionId, username);
        updateConversation(sessionId, messageSeq, title);
    }

    /**
     * 结束会话：将会话状态置为已结束。
     */
    @Override
    public void endConversation(String sessionId) {
        // （1）查询未删除会话，不存在则抛异常
        Optional<AiConversation> conversationOpt = aiConversationRepository.findBySessionIdAndDelFlag(sessionId, 0);
        if (conversationOpt.isEmpty()) {
            throw new ClientException("Conversation does not exist");
        }
        AiConversation conversation = conversationOpt.get();
        // （2）状态置为 2（已结束）并保存
        conversation.setStatus(2);
        conversation.setUpdateTime(new Date());
        aiConversationRepository.save(conversation);
    }

    /**
     * 校验会话归属后结束会话。
     */
    @Override
    public void endConversation(String sessionId, String username) {
        requireOwnedConversation(sessionId, username);
        endConversation(sessionId);
    }

    /**
     * 逻辑删除会话（置删除标记，不物理删除）。
     */
    @Override
    public void deleteConversation(String sessionId) {
        // （1）查询未删除会话，不存在则抛异常
        Optional<AiConversation> conversationOpt = aiConversationRepository.findBySessionIdAndDelFlag(sessionId, 0);
        if (conversationOpt.isEmpty()) {
            throw new ClientException("Conversation does not exist");
        }
        AiConversation conversation = conversationOpt.get();
        // （2）设置删除标记与更新时间后保存
        conversation.setDelFlag(1);
        conversation.setUpdateTime(new Date());
        aiConversationRepository.save(conversation);
    }

    /**
     * 校验会话归属后逻辑删除会话。
     */
    @Override
    public void deleteConversation(String sessionId, String username) {
        requireOwnedConversation(sessionId, username);
        deleteConversation(sessionId);
    }

    /**
     * 按 sessionId 查询会话详情，并回填关联 AI 的名称。
     */
    @Override
    public AiConversationRespDTO getConversationBySessionId(String sessionId) {
        // （1）查询未删除会话，不存在则抛异常
        Optional<AiConversation> conversationOpt = aiConversationRepository.findBySessionIdAndDelFlag(sessionId, 0);
        if (conversationOpt.isEmpty()) {
            throw new ClientException("Conversation does not exist");
        }
        AiConversation conversation = conversationOpt.get();
        // （2）复制属性为响应对象
        AiConversationRespDTO respDTO = new AiConversationRespDTO();
        BeanUtil.copyProperties(conversation, respDTO);
        // （3）回填 AI 名称
        AiPropertiesDO aiProperties = aiPropertiesService.getById(conversation.getAiId());
        if (aiProperties != null) {
            respDTO.setAiName(aiProperties.getAiName());
        }
        return respDTO;
    }

    /**
     * 校验会话归属后查询会话详情。
     */
    @Override
    public AiConversationRespDTO getConversationBySessionId(String sessionId, String username) {
        requireOwnedConversation(sessionId, username);
        return getConversationBySessionId(sessionId);
    }

    /**
     * 校验会话存在且属于指定用户，校验失败时抛出 ClientException。
     */
    @Override
    public void requireOwnedConversation(String sessionId, String username) {
        // （1）参数非空校验
        if (StrUtil.isBlank(sessionId)) {
            throw new ClientException("sessionId cannot be empty");
        }
        if (StrUtil.isBlank(username)) {
            throw new ClientException("username cannot be empty");
        }
        // （2）查询未删除会话并校验存在
        AiConversation conversation = aiConversationRepository
                .findBySessionIdAndDelFlag(sessionId, 0)
                .orElseThrow(() -> new ClientException("Conversation does not exist"));
        // （3）校验会话归属当前用户
        if (!username.equals(conversation.getUsername())) {
            throw new ClientException("No permission to access this conversation");
        }
    }

    /**
     * 查询指定用户拥有的所有未删除会话 id（去重）。
     */
    @Override
    public List<String> listOwnedSessionIds(String username) {
        // （1）校验用户名
        if (StrUtil.isBlank(username)) {
            throw new ClientException("username cannot be empty");
        }
        // （2）查询会话并提取非空、去重的 sessionId
        return aiConversationRepository.findByUsernameAndDelFlagOrderByCreateTimeDesc(username, 0)
                .stream()
                .map(AiConversation::getSessionId)
                .filter(StrUtil::isNotBlank)
                .distinct()
                .collect(Collectors.toList());
    }

    /**
     * 根据首条消息生成会话标题：空则用默认标题，超过 20 个字符则截断并加省略号。
     */
    private String generateTitle(String firstMessage) {
        if (StrUtil.isBlank(firstMessage)) {
            return "New Conversation";
        }
        if (firstMessage.length() <= 20) {
            return firstMessage;
        }
        return firstMessage.substring(0, 20) + "...";
    }
}
