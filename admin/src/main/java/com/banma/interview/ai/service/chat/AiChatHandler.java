package com.banma.interview.ai.service.chat;

import com.banma.interview.ai.dao.entity.AiPropertiesDO;
import com.banma.interview.ai.api.io.resp.AiMessageHistoryRespDTO;
import com.banma.interview.toolkit.xunfei.AIContentAccumulator;
import reactor.core.publisher.FluxSink;

import java.util.List;

public interface AiChatHandler {
    String getType();

    void streamToSink(AiPropertiesDO aiProperties, String userMessage, List<AiMessageHistoryRespDTO> historyMessages,
                      FluxSink<String> sink, AIContentAccumulator accumulator) throws Exception;
}

