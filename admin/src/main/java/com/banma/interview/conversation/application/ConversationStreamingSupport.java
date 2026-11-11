package com.banma.interview.conversation.application;

import com.banma.interview.toolkit.xunfei.AIContentAccumulator;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 会话流式处理下沉组件：统一编排历史加载、用户消息落库、流式输出、助手消息落库与回调。
 */
@Service
@Slf4j
public class ConversationStreamingSupport {

    /**
     * 执行一次完整的会话流式处理；发生异常时落库错误消息并回调错误处理。
     */
    public <H> void execute(ConversationStreamRequest<H> request) {
        // （1）记录开始时间
        long startTime = System.currentTimeMillis();
        try {
            // （2）加载历史消息并保存用户消息
            List<H> historyMessages = request.historySupplier.get();
            request.userMessageSaver.run();
            request.streamExecutor.execute(historyMessages, request.accumulator);

            // （3）执行流式输出并计算本次响应耗时
            int responseTime = (int) (System.currentTimeMillis() - startTime);
            // （4）保存助手消息，更新会话并执行成功回调
            int assistantMessageSeq = request.assistantMessageSaver.apply(new AssistantMessagePayload(
                    request.accumulator.getFullContent(),
                    request.accumulator.getFullReasoningContent(),
                    responseTime,
                    null
            ));
            if (request.conversationUpdater != null) {
                request.conversationUpdater.accept(assistantMessageSeq);
            }
            if (request.successHandler != null) {
                request.successHandler.run();
            }
        } catch (Exception ex) {
            log.error("Conversation streaming failed, sessionId={}", request.sessionId, ex);
            // （5）异常时保存错误消息，并执行错误回调
            int responseTime = (int) (System.currentTimeMillis() - startTime);
            try {
                request.assistantMessageSaver.apply(new AssistantMessagePayload(
                        request.defaultErrorContent,
                        null,
                        responseTime,
                        ex.getMessage()
                ));
            } catch (Exception persistenceEx) {
                log.error("Failed to persist conversation error message, sessionId={}",
                        request.sessionId, persistenceEx);
            }
            if (request.errorHandler != null) {
                request.errorHandler.accept(ex);
            }
        }
    }

    /**
     * 流式处理请求参数：装配各阶段所需的回调与执行器。
     */
    @Getter
    @Builder
    public static class ConversationStreamRequest<H> {
        // 会话 id
        private final String sessionId;
        // 出错时写入的默认错误内容
        private final String defaultErrorContent;
        // 内容累计器，用于汇总助手消息
        private final AIContentAccumulator accumulator;
        // 历史消息提供者
        private final ConversationHistorySupplier<H> historySupplier;
        // 用户消息保存动作
        private final CheckedRunnable userMessageSaver;
        // 流式输出执行器
        private final ConversationStreamExecutor<H> streamExecutor;
        // 助手消息保存器，返回消息序号
        private final Function<AssistantMessagePayload, Integer> assistantMessageSaver;
        // 会话更新回调，入参为助手消息序号
        private final Consumer<Integer> conversationUpdater;
        // 成功回调
        private final Runnable successHandler;
        // 异常回调
        private final Consumer<Exception> errorHandler;
    }

    /**
     * 助手消息落库载荷：正文、推理内容、响应耗时与错误信息。
     */
    public record AssistantMessagePayload(
            String content,
            String reasoningContent,
            int responseTime,
            String errorMessage
    ) {
    }

    /**
     * 历史消息提供者，允许抛出异常。
     */
    @FunctionalInterface
    public interface ConversationHistorySupplier<H> {
        /**
         * 获取历史消息列表。
         */
        List<H> get() throws Exception;
    }

    /**
     * 流式输出执行器，允许抛出异常。
     */
    @FunctionalInterface
    public interface ConversationStreamExecutor<H> {
        /**
         * 使用历史消息与累计器执行流式输出。
         */
        void execute(List<H> historyMessages, AIContentAccumulator accumulator) throws Exception;
    }

    /**
     * 可抛出异常的无参动作。
     */
    @FunctionalInterface
    public interface CheckedRunnable {
        /**
         * 执行动作。
         */
        void run() throws Exception;
    }
}
