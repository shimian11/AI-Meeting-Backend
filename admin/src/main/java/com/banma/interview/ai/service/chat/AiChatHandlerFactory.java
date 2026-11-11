package com.banma.interview.ai.service.chat;

import cn.hutool.core.util.StrUtil;
import org.springframework.stereotype.Component;

/**
 * AI聊天处理器工厂
 * 目前统一使用UniversalAiChatHandler处理所有兼容OpenAI协议的模型
 */
@Component
public class AiChatHandlerFactory {
    
    private final UniversalAiChatHandler universalAiChatHandler;

    /**
     * 构造注入通用聊天处理器。
     */
    public AiChatHandlerFactory(UniversalAiChatHandler universalAiChatHandler) {
        this.universalAiChatHandler = universalAiChatHandler;
    }

    /**
     * 根据 AI 类型返回对应处理器，类型为空或不受支持时返回 null。
     */
    public AiChatHandler getHandler(String aiType) {
        // （1）类型为空时直接返回 null
        if (StrUtil.isBlank(aiType)) {
            return null;
        }
        // 通用处理器支持所有兼容OpenAI协议的模型
        if (universalAiChatHandler.supports(aiType)) {
            return universalAiChatHandler;
        }
        return null;
    }
}
