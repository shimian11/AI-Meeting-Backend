package com.banma.interview.agent.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSON;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.banma.interview.agent.api.io.resp.AgentMessageHistoryRespDTO;
import com.banma.interview.agent.application.AgentResolver;
import com.banma.interview.agent.dao.entity.AgentPropertiesDO;
import com.banma.interview.agent.service.AgentConversationService;
import com.banma.interview.agent.service.AgentMessageService;
import com.banma.interview.common.convention.exception.ClientException;
import com.banma.interview.common.enums.AgentErrorCodeEnum;
import com.banma.interview.conversation.application.ConversationMessageHistoryService;
import com.banma.interview.conversation.application.ConversationMessagePersistenceService;
import com.banma.interview.conversation.application.ConversationOwnershipService;
import com.banma.interview.conversation.application.ConversationStreamingSupport;
import com.banma.interview.common.streaming.AIContentAccumulator;
import com.banma.interview.agent.application.cache.AgentPropertiesCache;
import com.banma.interview.user.api.io.req.UserMessageReqDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 智能体消息服务实现：负责 SSE 流式对话编排与消息历史查询。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AgentMessageServiceImpl implements AgentMessageService {

    private static final String DEFAULT_ERROR_CONTENT = "Sorry, an error occurred while processing your request.";

    private final AgentResolver agentResolver;
    private final AgentPropertiesCache agentPropertiesLoader;
    private final AgentConversationService agentConversationService;
    private final ConversationOwnershipService conversationOwnershipService;
    private final ConversationMessageHistoryService conversationMessageHistoryService;
    private final ConversationMessagePersistenceService conversationMessagePersistenceService;
    private final ConversationStreamingSupport conversationStreamingSupport;
    private final ThreadPoolTaskExecutor threadPoolTaskExecutor;

    /**
     * 查询指定会话的智能体历史消息。
     */
    @Override
    public List<AgentMessageHistoryRespDTO> getConversationHistory(String sessionId) {
        return conversationMessageHistoryService.listAgentHistory(sessionId);
    }

    /**
     * 校验会话归属后查询该会话的智能体历史消息。
     */
    @Override
    public List<AgentMessageHistoryRespDTO> getConversationHistory(String sessionId, Long userId) {
        conversationOwnershipService.requireOwnedConversation(sessionId, userId);
        return getConversationHistory(sessionId);
    }

    /**
     * 分页查询智能体历史消息：指定会话时按会话查询，否则查询全部。
     */
    @Override
    public IPage<AgentMessageHistoryRespDTO> pageHistoryMessages(String sessionId, Integer current, Integer size) {
        // （1）按是否指定 sessionId 选择查询范围
        if (StrUtil.isNotBlank(sessionId)) {
            return conversationMessageHistoryService.pageAgentHistory(sessionId, current, size);
        }
        return conversationMessageHistoryService.pageAllAgentHistory(current, size);
    }

    /**
     * 分页查询当前用户可见的智能体历史消息：指定会话时校验归属，否则按用户持有的会话查询。
     */
    @Override
    public IPage<AgentMessageHistoryRespDTO> pageHistoryMessages(
            String sessionId,
            Integer current,
            Integer size,
            Long userId) {
        // （1）指定会话时校验归属并按会话分页
        if (StrUtil.isNotBlank(sessionId)) {
            conversationOwnershipService.requireOwnedConversation(sessionId, userId);
            return pageHistoryMessages(sessionId, current, size);
        }

        // （2）未指定会话时按用户持有的会话分页，无会话则返回空页
        List<String> sessionIds = conversationOwnershipService.listOwnedSessionIds(userId);
        if (CollUtil.isEmpty(sessionIds)) {
            Page<AgentMessageHistoryRespDTO> emptyPage = new Page<>(current, size);
            emptyPage.setTotal(0);
            emptyPage.setRecords(Collections.emptyList());
            return emptyPage;
        }
        return conversationMessageHistoryService.pageAgentHistory(sessionIds, current, size);
    }

    /**
     * 发起智能体 SSE 对话：校验参数与智能体后，异步推送流式响应。
     */
    @Override
    public SseEmitter agentChatSse(UserMessageReqDTO requestParam) {
        // （1）校验 sessionId 非空
        String sessionId = requestParam.getSessionId();
        if (StrUtil.isBlank(sessionId)) {
            throw new ClientException("sessionId cannot be empty");
        }

        // （2）解析会话绑定的智能体
        SseEmitter emitter = new SseEmitter(18000L);
        Long agentId = agentResolver.resolveAgentId(sessionId, null);
        if (agentId == null) {
            throw new ClientException(AgentErrorCodeEnum.Agent_NULL);
        }
        // （3）提交异步处理任务，并注册超时回调
        String userMessage = requestParam.getInputMessage() == null ? "No input" : requestParam.getInputMessage();
        AIContentAccumulator accumulator = new AIContentAccumulator();

        threadPoolTaskExecutor.submit(() -> processChat(sessionId, agentId, userMessage, emitter, accumulator));
        emitter.onTimeout(emitter::complete);
        return emitter;
    }

    /**
     * 校验会话归属后发起智能体 SSE 对话。
     */
    @Override
    public SseEmitter agentChatSse(UserMessageReqDTO requestParam, Long userId) {
        // （1）取请求中的 sessionId 并校验归属
        String sessionId = requestParam != null ? requestParam.getSessionId() : null;
        conversationOwnershipService.requireOwnedConversation(sessionId, userId);
        // （2）复用无用户维度的 SSE 处理
        return agentChatSse(requestParam);
    }

    /**
     * 编排一次智能体 SSE 对话：加载历史、保存用户消息、流式输出、落库助手消息并回调结束。
     */
    private void processChat(
            String sessionId,
            Long agentId,
            String userMessage,
            SseEmitter emitter,
            AIContentAccumulator accumulator) {
        // （1）构建流式处理请求，交由统一下沉组件执行
        conversationStreamingSupport.execute(ConversationStreamingSupport.ConversationStreamRequest
                .<AgentMessageHistoryRespDTO>builder()
                .sessionId(sessionId)
                .defaultErrorContent(DEFAULT_ERROR_CONTENT)
                .accumulator(accumulator)
                // （2）绑定历史查询与用户消息保存
                .historySupplier(() -> conversationMessageHistoryService.listAgentHistory(sessionId))
                .userMessageSaver(() -> conversationMessagePersistenceService.saveAgentUserMessage(sessionId, userMessage))
                // （3）流式执行器：加载智能体配置并调用星辰接口，将输出写入 SSE
                .streamExecutor((historyMessages, contentAccumulator) -> {
                    // 星辰链路已下线（2026-11-11）；通用对话 SSE 将切换到 Python Agent（6C）
                    throw new ClientException("通用对话流式链路切换中：讯飞星辰已下线，Python Agent 将于 6C 接入");
                })
                // （4）助手消息落库，并回写会话消息数
                .assistantMessageSaver(payload -> conversationMessagePersistenceService.saveAgentAssistantMessage(
                        sessionId,
                        payload.content(),
                        payload.responseTime(),
                        payload.errorMessage()))
                .conversationUpdater(messageSeq -> agentConversationService.updateConversation(sessionId, messageSeq, null))
                // （5）成功回调：发送结束事件并正常完成 emitter
                .successHandler(() -> {
                    try {
                        emitter.send(SseEmitter.event().name("end").data("[DONE]"));
                        emitter.complete();
                    } catch (IOException ex) {
                        log.warn("Failed to send agent stream completion event, sessionId={}", sessionId, ex);
                        emitter.completeWithError(ex);
                    }
                })
                // （6）异常回调：以错误结束 emitter
                .errorHandler(emitter::completeWithError)
                .build());
    }

    /**
     * 将历史消息转换为星辰接口所需的多轮对话 JSON。
     */
    private String buildHistoryJson(List<AgentMessageHistoryRespDTO> historyMessages) {
        List<HashMap<String, String>> historyList = new ArrayList<>();
        // （1）历史非空时逐条映射为 role/content 结构
        if (CollUtil.isNotEmpty(historyMessages)) {
            historyList = historyMessages.stream().map(history -> {
                HashMap<String, String> item = new HashMap<>();
                item.put("role", history.getMessageType() == 1 ? "user" : "assistant");
                item.put("content_type", "text");
                item.put("content", history.getMessageContent());
                return item;
            }).collect(Collectors.toList());
        }
        // （2）序列化为 JSON 字符串
        return JSON.toJSONString(historyList);
    }
}
