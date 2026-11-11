package com.banma.interview.ai.service.impl;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.banma.interview.ai.api.io.req.AiMessageReqDTO;
import com.banma.interview.ai.api.io.resp.AiMessageHistoryRespDTO;
import com.banma.interview.ai.dao.entity.AiPropertiesDO;
import com.banma.interview.ai.service.AiConversationService;
import com.banma.interview.ai.service.AiMessageService;
import com.banma.interview.ai.service.AiPropertiesService;
import com.banma.interview.ai.service.chat.AiChatHandler;
import com.banma.interview.ai.service.chat.AiChatHandlerFactory;
import com.banma.interview.auth.application.CurrentUserService;
import com.banma.interview.common.convention.exception.ClientException;
import com.banma.interview.conversation.application.ConversationMessageHistoryService;
import com.banma.interview.conversation.application.ConversationMessagePersistenceService;
import com.banma.interview.conversation.application.ConversationStreamingSupport;
import com.banma.interview.toolkit.xunfei.AIContentAccumulator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.util.Collections;
import java.util.List;

/**
 * AI 消息服务实现：负责流式对话编排、对话历史查询与分页。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiMessageServiceImpl implements AiMessageService {

    private static final String DEFAULT_ERROR_CONTENT = "Sorry, an error occurred while processing your request.";
    private static final String UNSUPPORTED_AI_TYPE = "Current AI type is not supported";

    private final AiPropertiesService aiPropertiesService;
    private final AiConversationService aiConversationService;
    private final CurrentUserService currentUserService;
    private final AiChatHandlerFactory aiChatHandlerFactory;
    private final ConversationMessageHistoryService conversationMessageHistoryService;
    private final ConversationMessagePersistenceService conversationMessagePersistenceService;
    private final ConversationStreamingSupport conversationStreamingSupport;
    private final ThreadPoolTaskExecutor threadPoolTaskExecutor;

    /**
     * 以请求体中携带的用户名发起流式对话。
     */
    @Override
    public Flux<String> aiChatFlux(AiMessageReqDTO requestParam) {
        String username = requestParam == null ? null : requestParam.getUserName();
        return aiChatFlux(requestParam, username);
    }

    /**
     * 校验请求参数与会话归属后，返回 AI 流式响应。
     */
    @Override
    public Flux<String> aiChatFlux(AiMessageReqDTO requestParam, String username) {
        // （1）基础参数校验（请求体、sessionId、username）
        if (requestParam == null) {
            return Flux.error(new ClientException("request body cannot be empty"));
        }
        if (StrUtil.isBlank(requestParam.getSessionId())) {
            return Flux.error(new ClientException("sessionId cannot be empty"));
        }
        if (StrUtil.isBlank(username)) {
            return Flux.error(new ClientException("username cannot be empty"));
        }
        // （2）校验会话归属并回填用户名
        aiConversationService.requireOwnedConversation(requestParam.getSessionId(), username);
        requestParam.setUserName(username);
        return aiChatFluxInternal(requestParam);
    }

    /**
     * 内部流式对话入口：校验参数后创建 Flux，并在独立线程中异步处理对话。
     */
    private Flux<String> aiChatFluxInternal(AiMessageReqDTO requestParam) {
        // （1）再次校验请求体与 sessionId
        if (requestParam == null) {
            return Flux.error(new ClientException("request body cannot be empty"));
        }

        String sessionId = requestParam.getSessionId();
        if (StrUtil.isBlank(sessionId)) {
            return Flux.error(new ClientException("sessionId cannot be empty"));
        }

        // （2）创建 Flux：提交异步处理任务，并注册取消与销毁回调
        return Flux.create(sink -> {
            String userMessage = StrUtil.blankToDefault(requestParam.getInputMessage(), "No input");
            Long aiId = requestParam.getAiId();
            AIContentAccumulator accumulator = new AIContentAccumulator();

            threadPoolTaskExecutor.submit(() -> processChat(sessionId, aiId, userMessage, sink, accumulator));
            sink.onCancel(() -> log.warn("AI chat flux cancelled, sessionId={}", sessionId));
            sink.onDispose(() -> log.info("AI chat flux disposed, sessionId={}", sessionId));
        });
    }

    /**
     * 编排一次 AI 对话的流式处理：加载历史、保存用户消息、流式输出、落库助手消息并回调结束。
     */
    private void processChat(
            String sessionId,
            Long aiId,
            String userMessage,
            FluxSink<String> sink,
            AIContentAccumulator accumulator) {
        // （1）构建流式处理请求，交由统一下沉组件执行
        conversationStreamingSupport.execute(ConversationStreamingSupport.ConversationStreamRequest
                .<AiMessageHistoryRespDTO>builder()
                .sessionId(sessionId)
                .defaultErrorContent(DEFAULT_ERROR_CONTENT)
                .accumulator(accumulator)
                // （2）绑定历史查询与用户消息保存
                .historySupplier(() -> conversationMessageHistoryService.listAiHistory(sessionId))
                .userMessageSaver(() -> conversationMessagePersistenceService.saveAiUserMessage(sessionId, userMessage))
                // （3）流式执行器：解析 AI 配置与处理器，将模型输出写入 sink
                .streamExecutor((historyMessages, contentAccumulator) -> {
                    AiPropertiesDO aiProperties = resolveAiProperties(aiId);
                    AiChatHandler handler = aiChatHandlerFactory.getHandler(aiProperties.getAiType());
                    // 无匹配处理器时向流写入“类型不支持”提示并返回
                    if (handler == null) {
                        sendUnsupportedSink(sink, contentAccumulator);
                        return;
                    }
                    handler.streamToSink(aiProperties, userMessage, historyMessages, sink, contentAccumulator);
                })
                // （4）助手消息落库（含推理内容、响应耗时与错误信息）
                .assistantMessageSaver(payload -> conversationMessagePersistenceService.saveAiAssistantMessage(
                        sessionId,
                        payload.content(),
                        payload.reasoningContent(),
                        payload.responseTime(),
                        payload.errorMessage()))
                // （5）用助手消息序号回写会话的消息数
                .conversationUpdater(messageSeq -> aiConversationService.updateConversation(sessionId, messageSeq, null))
                // （6）成功回调：流未被取消时正常结束
                .successHandler(() -> {
                    if (!sink.isCancelled()) {
                        sink.complete();
                    }
                })
                // （7）异常回调：流未被取消时推送错误提示并传播异常
                .errorHandler(ex -> {
                    if (!sink.isCancelled()) {
                        sink.next(DEFAULT_ERROR_CONTENT);
                        sink.error(ex);
                    }
                })
                .build());
    }

    /**
     * 解析本次对话使用的 AI 配置：未指定 aiId 时取默认豆包配置，否则校验指定配置可用。
     */
    private AiPropertiesDO resolveAiProperties(Long aiId) {
        AiPropertiesDO aiProperties;
        // （1）未指定 aiId 时取默认豆包配置
        if (aiId == null) {
            aiProperties = aiPropertiesService.getDefaultDoubaoConfig();
            if (aiProperties == null) {
                throw new ClientException("Default AI config does not exist");
            }
        } else {
            // （2）指定 aiId 时校验存在、未删除且已启用
            aiProperties = aiPropertiesService.getById(aiId);
            if (aiProperties == null || aiProperties.getDelFlag() == 1 || aiProperties.getIsEnabled() == 0) {
                throw new ClientException("AI config does not exist or is disabled");
            }
        }
        return aiProperties;
    }

    /**
     * 查询指定会话的 AI 历史消息（按消息序号升序）。
     */
    @Override
    public List<AiMessageHistoryRespDTO> getConversationHistory(String sessionId) {
        return conversationMessageHistoryService.listAiHistory(sessionId);
    }

    /**
     * 校验会话归属后查询该会话的 AI 历史消息。
     */
    @Override
    public List<AiMessageHistoryRespDTO> getConversationHistory(String sessionId, String username) {
        aiConversationService.requireOwnedConversation(sessionId, username);
        return getConversationHistory(sessionId);
    }

    /**
     * 分页查询 AI 历史消息：指定会话时按会话查询，否则查询全部。
     */
    @Override
    public IPage<AiMessageHistoryRespDTO> pageHistoryMessages(String sessionId, Integer current, Integer size) {
        // （1）按是否指定 sessionId 选择查询范围
        if (StrUtil.isNotBlank(sessionId)) {
            return conversationMessageHistoryService.pageAiHistory(sessionId, current, size);
        }
        return conversationMessageHistoryService.pageAllAiHistory(current, size);
    }

    /**
     * 分页查询当前用户可见的 AI 历史消息：指定会话时校验归属，否则按用户持有的会话查询。
     */
    @Override
    public IPage<AiMessageHistoryRespDTO> pageHistoryMessages(
            String sessionId,
            Integer current,
            Integer size,
            String username) {
        // （1）指定会话时校验归属并按会话分页
        if (StrUtil.isNotBlank(sessionId)) {
            aiConversationService.requireOwnedConversation(sessionId, username);
            return pageHistoryMessages(sessionId, current, size);
        }

        // （2）未指定会话时按用户持有的会话分页，无会话则返回空页
        List<String> ownedSessionIds = aiConversationService.listOwnedSessionIds(username);
        if (CollUtil.isEmpty(ownedSessionIds)) {
            Page<AiMessageHistoryRespDTO> emptyPage = new Page<>(current, size);
            emptyPage.setTotal(0);
            emptyPage.setRecords(Collections.emptyList());
            return emptyPage;
        }
        return conversationMessageHistoryService.pageAiHistory(ownedSessionIds, current, size);
    }

    /**
     * 按用户名查询用户 id。
     */
    @Override
    public Long getUserIdByUsername(String username) {
        return currentUserService.getUserIdByUsername(username);
    }

    /**
     * 向流中写入“当前 AI 类型不支持”的提示，并同步记录到内容累计器。
     */
    private void sendUnsupportedSink(FluxSink<String> sink, AIContentAccumulator accumulator) {
        if (!sink.isCancelled()) {
            sink.next(UNSUPPORTED_AI_TYPE);
        }
        accumulator.appendSimpleContent(UNSUPPORTED_AI_TYPE);
    }
}
