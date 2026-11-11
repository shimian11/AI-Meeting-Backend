package com.banma.interview.media.application;

import jakarta.annotation.Resource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;

/**
 * Application service for websocket session status and server-side push.
 * WebSocket 消息推送应用服务：封装向指定用户推送消息、查询在线状态以及批量推送能力。
 */
@Slf4j
@Service
public class WebSocketMessageService {

    @Resource(name = "queryExecutor")
    private ExecutorService queryExecutor;

    /**
     * 向指定用户同步推送一条 WebSocket 消息，发送失败时记录日志并返回 false。
     */
    public boolean sendMessageToUser(String userId, String type, String message, String data) {
        // 语音转写通道已下线（讯飞 ASR 清理，2026-11-11）：统一返回失败，调用方按不可达处理
        log.warn("WebSocket push skipped: transcription channel offline, userId={}, type={}", userId, type);
        return false;
    }

    /**
     * 异步向指定用户推送 WebSocket 消息，使用查询线程池执行。
     */
    public CompletableFuture<Boolean> sendMessageToUserAsync(String userId, String type, String message, String data) {
        return CompletableFuture.supplyAsync(() -> sendMessageToUser(userId, type, message, data), queryExecutor);
    }

    /**
     * 判断指定用户当前是否在线。
     */
    public boolean isUserOnline(String userId) {
        return false; // 语音转写通道已下线，无在线用户
    }

    /**
     * 向指定用户推送系统通知消息。
     */
    public boolean sendSystemNotification(String userId, String message) {
        return sendMessageToUser(userId, "system_notification", message, null);
    }

    /**
     * 推送语音转写结果，根据是否为最终结果选择 final 或 transcription 消息类型。
     */
    public boolean sendTranscriptionResult(String userId, String result, boolean isFinal) {
        String type = isFinal ? "final" : "transcription";
        return sendMessageToUser(userId, type, "Transcription result", result);
    }

    /**
     * 向指定用户推送错误消息。
     */
    public boolean sendErrorMessage(String userId, String errorMessage) {
        return sendMessageToUser(userId, "error", errorMessage, null);
    }

    /**
     * 向指定用户推送状态更新消息及其详情。
     */
    public boolean sendStatusUpdate(String userId, String status, String details) {
        return sendMessageToUser(userId, "status_update", status, details);
    }

    /**
     * 向一组用户逐个推送消息，仅对在线用户发送。
     */
    public void sendMessageToUsers(Set<String> userIds, String type, String message, String data) {
        // （1）遍历用户集合，过滤离线用户后逐个推送
        userIds.forEach(userId -> {
            if (isUserOnline(userId)) {
                sendMessageToUser(userId, type, message, data);
            }
        });
    }

    /**
     * 异步向一组用户推送消息，使用查询线程池执行。
     */
    public CompletableFuture<Void> sendMessageToUsersAsync(Set<String> userIds, String type, String message, String data) {
        return CompletableFuture.runAsync(() -> sendMessageToUsers(userIds, type, message, data), queryExecutor);
    }
}
