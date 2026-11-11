package com.banma.interview.media.infrastructure.websocket;

import com.alibaba.fastjson2.JSON;
import com.banma.interview.auth.application.WebSocketAuthService;
import com.banma.interview.media.infrastructure.integration.XunfeiAudioService;
import com.banma.interview.media.infrastructure.integration.XunfeiAudioService.RealtimeTranscriptionUpdate;
import jakarta.websocket.CloseReason;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.io.Closeable;
import java.io.IOException;
import java.io.PipedInputStream;
import java.io.PipedOutputStream;
import java.nio.ByteBuffer;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Real-time speech-to-text WebSocket endpoint.
 * 实时语音转写 WebSocket 端点：负责连接鉴权、心跳维护、音频流接收与识别结果推送。
 */
@Slf4j
@Component
@ServerEndpoint(value = "/api/banma/v1/xunfei/audio-to-text/{userId}")
public class AudioTranscriptionWebSocketHandler {

    private static volatile XunfeiAudioService xunfeiAudioService;
    private static volatile WebSocketAuthService webSocketAuthService;
    private static volatile ScheduledExecutorService heartbeatExecutor;

    /**
     * 由 Spring 注入讯飞音频服务，供端点实例通过静态字段访问。
     */
    @Autowired
    public void setXunfeiAudioService(XunfeiAudioService service) {
        AudioTranscriptionWebSocketHandler.xunfeiAudioService = service;
    }

    /**
     * 由 Spring 注入 WebSocket 鉴权服务。
     */
    @Autowired
    public void setWebSocketAuthService(WebSocketAuthService service) {
        AudioTranscriptionWebSocketHandler.webSocketAuthService = service;
    }

    /**
     * 由 Spring 注入调度线程池，用于定时发送心跳。
     */
    @Autowired
    public void setHeartbeatExecutor(@Qualifier("scheduledExecutorService") ScheduledExecutorService scheduledExecutorService) {
        AudioTranscriptionWebSocketHandler.heartbeatExecutor = scheduledExecutorService;
    }

    private static final ConcurrentMap<String, Session> USER_SESSIONS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, String> SESSION_USER_MAP = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, TranscriptionSessionContext> TRANSCRIPTION_CONTEXTS = new ConcurrentHashMap<>();
    private static final ConcurrentMap<String, ScheduledFuture<?>> HEARTBEAT_TASKS = new ConcurrentHashMap<>();

    /**
     * 处理连接建立：校验身份、登记用户会话、回送连接成功消息并启动心跳。
     */
    @OnOpen
    public void onOpen(Session session, @PathParam("userId") String userId) {
        // （1）校验连接用户身份，未通过则直接关闭连接
        if (!isAuthorizedUser(session, userId)) {
            log.warn("WebSocket auth failed, userId={}, sessionId={}", userId, session.getId());
            closeSession(session, "Unauthorized websocket connection");
            return;
        }

        // （2）登记用户与会话的双向映射
        String sessionId = session.getId();
        USER_SESSIONS.put(userId, session);
        SESSION_USER_MAP.put(sessionId, userId);
        log.info("WebSocket connected, userId={}, sessionId={}", userId, sessionId);

        // （3）回送连接成功消息并启动定时心跳
        sendMessage(session, createResponse("connected", "WebSocket connected", userId));
        startHeartbeat(session);
    }

    /**
     * 校验 WebSocket 连接的用户身份，鉴权服务未注入时拒绝连接。
     */
    private boolean isAuthorizedUser(Session session, String pathUserId) {
        if (webSocketAuthService == null) {
            log.error("WebSocketAuthService is not injected, reject websocket connection");
            return false;
        }
        return webSocketAuthService.isAuthorized(session, pathUserId);
    }

    /**
     * 以违反策略的原因码关闭会话，关闭失败仅记录告警。
     */
    private void closeSession(Session session, String reason) {
        if (session == null) {
            return;
        }
        try {
            if (session.isOpen()) {
                session.close(new CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY, reason));
            }
        } catch (IOException ex) {
            log.warn("Failed to close websocket session, sessionId={}", session.getId(), ex);
        }
    }

    /**
     * 处理文本消息：解析控制指令并分发；解析失败时按普通文本回显。
     */
    @OnMessage
    public void onMessage(Session session, String message) {
        // （1）获取会话对应的用户并记录消息
        String userId = SESSION_USER_MAP.get(session.getId());
        log.info("Received text message, userId={}, message={}", userId, message);

        try {
            // （2）解析控制消息并分发处理
            WebSocketMessage wsMessage = JSON.parseObject(message, WebSocketMessage.class);
            handleControlMessage(session, userId, wsMessage);
        } catch (Exception ex) {
            // （3）解析失败时按普通文本消息回显
            sendMessage(session, createResponse("info", "Received text message: " + message, null));
        }
    }

    /**
     * 处理二进制消息：将收到的音频块写入当前转写会话的管道。
     */
    @OnMessage
    public void onMessage(Session session, ByteBuffer byteBuffer) {
        String sessionId = session.getId();
        String userId = SESSION_USER_MAP.get(sessionId);
        log.debug("Received audio chunk, userId={}, sessionId={}, bytes={}",
                userId, sessionId, byteBuffer.remaining());

        try {
            // （1）读取音频字节
            byte[] audioData = new byte[byteBuffer.remaining()];
            byteBuffer.get(audioData);

            // （2）检查转写会话是否已启动
            TranscriptionSessionContext context = TRANSCRIPTION_CONTEXTS.get(sessionId);
            if (context == null || !context.active.get()) {
                log.warn("Audio chunk received before transcription session started, userId={}, sessionId={}",
                        userId, sessionId);
                sendMessage(session, createResponse("error",
                        "Transcription session is not started. Send start_transcription first.", null));
                return;
            }

            // （3）将音频块写入转写会话的管道输出流
            context.audioOutputStream.write(audioData);
            context.audioOutputStream.flush();
        } catch (Exception ex) {
            // （4）处理异常时记录日志并回送错误消息
            log.error("Failed to process audio chunk, userId={}, sessionId={}", userId, sessionId, ex);
            sendMessage(session, createResponse("error", "Failed to process audio chunk: " + ex.getMessage(), null));
        }
    }

    /**
     * 处理连接关闭：停止转写、取消心跳并清理用户会话映射。
     */
    @OnClose
    public void onClose(Session session, CloseReason closeReason) {
        String sessionId = session.getId();
        String userId = SESSION_USER_MAP.get(sessionId);

        // （1）停止转写会话并取消心跳任务
        stopTranscriptionSession(sessionId);
        cancelHeartbeat(sessionId);

        // （2）清理用户与会话映射
        if (userId != null) {
            USER_SESSIONS.remove(userId);
            SESSION_USER_MAP.remove(sessionId);
        }
        // （3）记录关闭原因
        String reason = closeReason != null ? closeReason.getReasonPhrase() : "unknown";
        log.info("WebSocket closed, userId={}, sessionId={}, reason={}",
                userId, sessionId, reason);
    }

    /**
     * 处理连接异常：记录日志、清理转写与心跳资源并回送错误消息。
     */
    @OnError
    public void onError(Session session, Throwable error) {
        // （1）定位出错的会话与用户并记录日志
        String sessionId = session != null ? session.getId() : null;
        String userId = sessionId != null ? SESSION_USER_MAP.get(sessionId) : null;
        log.error("WebSocket error, userId={}, sessionId={}", userId, sessionId, error);

        // （2）清理转写会话与心跳任务
        if (sessionId != null) {
            stopTranscriptionSession(sessionId);
            cancelHeartbeat(sessionId);
        }
        // （3）回送错误消息
        sendMessage(session, createResponse("error", "WebSocket error: " + error.getMessage(), null));
    }

    /**
     * 分发控制指令：心跳、开始/停止转写、状态查询及未知指令处理。
     */
    private void handleControlMessage(Session session, String userId, WebSocketMessage message) {
        // （1）校验消息类型是否为空
        String type = message != null ? message.getType() : null;
        if (type == null) {
            sendMessage(session, createResponse("unknown_command", "Missing command type", null));
            return;
        }

        // （2）按指令类型分发处理
        switch (type) {
            case "ping" -> sendMessage(session, createResponse("pong", "pong", String.valueOf(System.currentTimeMillis())));
            case "start_transcription" -> startTranscriptionSession(session, userId);
            case "stop_transcription" -> {
                boolean stopped = stopTranscriptionSession(session.getId());
                if (stopped) {
                    sendMessage(session, createResponse("transcription_stopped", "Transcription stopped", null));
                } else {
                    sendMessage(session, createResponse("transcription_already_stopped",
                            "Transcription is already stopped", null));
                }
            }
            case "get_status" -> sendMessage(session, createResponse("status", "Connection is healthy", userId));
            default -> sendMessage(session, createResponse("unknown_command", "Unknown command: " + type, null));
        }
    }

    /**
     * 为指定会话启动定时心跳，每 30 秒推送一次心跳消息。
     */
    private void startHeartbeat(Session session) {
        // （1）调度线程池未注入时跳过心跳
        if (heartbeatExecutor == null) {
            log.warn("scheduledExecutorService is not injected, skip heartbeat, sessionId={}", session.getId());
            return;
        }
        // （2）取消该会话已有的心跳任务
        String sessionId = session.getId();
        ScheduledFuture<?> oldTask = HEARTBEAT_TASKS.remove(sessionId);
        if (oldTask != null) {
            oldTask.cancel(true);
        }

        // （3）创建并登记新的定时心跳任务
        ScheduledFuture<?> task = heartbeatExecutor.scheduleAtFixedRate(() -> {
            if (session.isOpen()) {
                sendMessage(session, createResponse("heartbeat", "heartbeat", String.valueOf(System.currentTimeMillis())));
            }
        }, 30, 30, TimeUnit.SECONDS);
        HEARTBEAT_TASKS.put(sessionId, task);
    }

    /**
     * 取消并移除指定会话的心跳任务。
     */
    private void cancelHeartbeat(String sessionId) {
        ScheduledFuture<?> task = HEARTBEAT_TASKS.remove(sessionId);
        if (task != null) {
            task.cancel(true);
        }
    }

    /**
     * 启动会话的实时转写：处理重复启动、替换旧会话并原子登记新会话。
     */
    private void startTranscriptionSession(Session session, String userId) {
        String sessionId = session.getId();
        // （1）已有活跃且未请求停止的会话时直接返回已启动
        TranscriptionSessionContext existing = TRANSCRIPTION_CONTEXTS.get(sessionId);
        if (existing != null && existing.active.get() && !existing.stopRequested.get()) {
            sendMessage(session, createResponse("transcription_already_started",
                    "Transcription is already started", null));
            return;
        }

        // （2）停止旧的转写会话
        stopTranscriptionSession(sessionId);

        // （3）创建新会话并处理并发登记的竞争情况
        TranscriptionSessionContext context = createAndStartTranscriptionSession(session, userId);
        if (context != null) {
            TranscriptionSessionContext raced = TRANSCRIPTION_CONTEXTS.putIfAbsent(sessionId, context);
            if (raced != null && raced.active.get() && !raced.stopRequested.get()) {
                context.active.set(false);
                context.stopRequested.set(true);
                closeQuietly(context.audioOutputStream);
                closeQuietly(context.audioInputStream);
                sendMessage(session, createResponse("transcription_already_started",
                        "Transcription is already started", null));
                return;
            }
            TRANSCRIPTION_CONTEXTS.put(sessionId, context);
            // （4）回送启动成功消息
            sendMessage(session, createResponse("transcription_started", "Transcription started", null));
        } else {
            sendMessage(session, createResponse("error", "Failed to start transcription", null));
        }
    }

    /**
     * 创建转写会话上下文并发起实时转写，回调中推送中间结果与最终结果。
     */
    private TranscriptionSessionContext createAndStartTranscriptionSession(Session session, String userId) {
        String sessionId = session.getId();
        try {
            // （1）校验讯飞音频服务是否已注入
            if (xunfeiAudioService == null) {
                log.error("XunfeiAudioService is not injected yet, cannot start transcription. sessionId={}", sessionId);
                return null;
            }
            // （2）创建音频管道流与会话上下文
            PipedInputStream audioInputStream = new PipedInputStream(64 * 1024);
            PipedOutputStream audioOutputStream = new PipedOutputStream(audioInputStream);
            AtomicBoolean active = new AtomicBoolean(true);
            TranscriptionSessionContext context = new TranscriptionSessionContext(audioInputStream, audioOutputStream, active);

            // （3）发起实时转写，每次增量更新都推送给前端
            CompletableFuture<String> future = xunfeiAudioService.realTimeAudioToText(audioInputStream, update ->
                    {
                        context.lastUpdate.set(update);
                        sendMessage(session, createResponse("transcription", "Partial snapshot", update, true));
                    }
            );

            // （4）转写完成或失败时推送结果并清理上下文
            future.whenComplete((finalResult, throwable) -> {
                if (throwable != null && !isExpectedStopException(context, throwable)) {
                    log.error("Transcription failed, userId={}, sessionId={}", userId, sessionId, throwable);
                    sendMessage(session, createResponse("error", "Transcription failed: " + throwable.getMessage(), null));
                } else {
                    log.info("Transcription finished, userId={}, sessionId={}", userId, sessionId);
                    if (!context.stopRequested.get() && finalResult != null) {
                        sendMessage(session, createResponse("final", "Transcription completed",
                                buildFinalUpdate(finalResult, context.lastUpdate.get()), true));
                    }
                }
                cleanupTranscriptionContext(sessionId, context);
            });
            return context;
        } catch (Exception ex) {
            log.error("Failed to create transcription session, userId={}, sessionId={}", userId, sessionId, ex);
            return null;
        }
    }

    /**
     * 停止指定会话的转写：移除上下文、标记停止并关闭音频输出流。
     *
     * @return 是否确实停止了正在进行的转写会话
     */
    private boolean stopTranscriptionSession(String sessionId) {
        // （1）移除并判断是否存在转写会话
        TranscriptionSessionContext context = TRANSCRIPTION_CONTEXTS.remove(sessionId);
        if (context == null) {
            return false;
        }
        // （2）标记会话停止并关闭音频输出流
        context.active.set(false);
        context.stopRequested.set(true);
        closeQuietly(context.audioOutputStream);
        return true;
    }

    /**
     * 清理转写上下文：以条件移除方式避免误删新会话，并关闭音频流。
     */
    private void cleanupTranscriptionContext(String sessionId, TranscriptionSessionContext context) {
        // （1）仅在映射仍指向当前上下文时移除
        TRANSCRIPTION_CONTEXTS.remove(sessionId, context);
        // （2）标记停止并关闭音频输入输出流
        context.active.set(false);
        closeQuietly(context.audioOutputStream);
        closeQuietly(context.audioInputStream);
    }

    /**
     * 判断转写异常是否属于主动停止导致的管道关闭，此类异常无需上报错误。
     */
    private boolean isExpectedStopException(TranscriptionSessionContext context, Throwable throwable) {
        // （1）未请求停止时不属于预期异常
        if (!context.stopRequested.get()) {
            return false;
        }
        // （2）沿异常链查找管道/流已关闭的错误信息
        Throwable cursor = throwable;
        while (cursor != null) {
            String msg = cursor.getMessage();
            if (msg != null && (msg.contains("Pipe closed") || msg.contains("Stream closed"))) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    /**
     * 静默关闭可关闭资源，忽略关闭异常。
     */
    private void closeQuietly(Closeable closeable) {
        if (closeable == null) {
            return;
        }
        try {
            closeable.close();
        } catch (IOException ignored) {
            // no-op
        }
    }

    /**
     * 向指定会话发送文本消息，仅在会话仍打开时发送，异常仅记录日志。
     */
    private void sendMessage(Session session, String message) {
        if (session != null && session.isOpen()) {
            try {
                session.getBasicRemote().sendText(message);
            } catch (IOException ex) {
                log.error("Failed to send message, sessionId={}", session.getId(), ex);
            }
        }
    }

    /**
     * 向指定用户发送消息（供其他服务调用），用户离线时直接返回。
     */
    public static void sendMessageToUser(String userId, String type, String message, String data) {
        // （1）查找用户会话并校验在线状态
        Session session = USER_SESSIONS.get(userId);
        if (session == null || !session.isOpen()) {
            log.warn("User is offline, userId={}", userId);
            return;
        }
        try {
            // （2）发送序列化后的消息
            session.getBasicRemote().sendText(createStaticResponse(type, message, data));
        } catch (IOException ex) {
            log.error("Failed to send message to user, userId={}", userId, ex);
        }
    }

    /**
     * 向所有在线用户广播同一条消息。
     */
    public static void broadcastMessage(String type, String message, String data) {
        String payload = createStaticResponse(type, message, data);
        // （1）遍历在线会话逐个发送广播消息
        USER_SESSIONS.forEach((userId, session) -> {
            if (session.isOpen()) {
                try {
                    session.getBasicRemote().sendText(payload);
                } catch (IOException ex) {
                    log.error("Broadcast failed, userId={}", userId, ex);
                }
            }
        });
    }

    /**
     * 返回当前在线用户的 id 集合。
     */
    public static Set<String> getOnlineUsers() {
        return USER_SESSIONS.keySet();
    }

    /**
     * 判断指定用户是否存在且会话仍打开。
     */
    public static boolean isUserOnline(String userId) {
        Session session = USER_SESSIONS.get(userId);
        return session != null && session.isOpen();
    }

    /**
     * 构建非快照消息的响应 JSON。
     */
    private String createResponse(String type, String message, String data) {
        return createResponse(type, message, data, false);
    }

    /**
     * 构建通用响应 JSON，填充全文、快照标记、更新动作与时间戳。
     */
    private String createResponse(String type, String message, String data, boolean isSnapshot) {
        WebSocketResponse response = new WebSocketResponse();
        response.setType(type);
        response.setMessage(message);
        response.setData(data);
        response.setFullText(resolveFullText(type, data));
        response.setIsSnapshot(isSnapshot);
        response.setUpdateAction(resolveUpdateAction(type));
        response.setTimestamp(System.currentTimeMillis());
        return JSON.toJSONString(response);
    }

    /**
     * 构建携带实时转写增量信息的响应 JSON。
     */
    private String createResponse(String type,
                                  String message,
                                  RealtimeTranscriptionUpdate update,
                                  boolean isSnapshot) {
        WebSocketResponse response = new WebSocketResponse();
        response.setType(type);
        response.setMessage(message);
        response.setData(update != null ? update.fullText() : null);
        response.setFullText(update != null ? update.fullText() : null);
        response.setDisplayText(update != null ? update.displayText() : null);
        response.setCommittedText(update != null ? update.committedText() : null);
        response.setLiveText(update != null ? update.liveText() : null);
        response.setRevision(update != null ? update.revision() : null);
        response.setResultStatus(update != null ? update.resultStatus() : null);
        response.setIsSnapshot(isSnapshot);
        response.setUpdateAction(resolveUpdateAction(type));
        response.setTimestamp(System.currentTimeMillis());
        if (update != null) {
            response.setSegmentId(update.segmentId());
            response.setSentenceSeq(update.segmentId());
            response.setSegmentText(update.segmentText());
            response.setPgs(update.pgs());
            response.setRg(update.rg());
            response.setBg(update.bg());
            response.setEd(update.ed());
            response.setIsFinalPacket(update.finalPacket());
        }
        return JSON.toJSONString(response);
    }

    /**
     * 基于最后一次增量更新构建最终结果，缺失时使用默认值兜底。
     */
    private RealtimeTranscriptionUpdate buildFinalUpdate(String finalResult,
                                                         RealtimeTranscriptionUpdate lastUpdate) {
        if (lastUpdate == null) {
            return new RealtimeTranscriptionUpdate(
                    finalResult,
                    finalResult,
                    "",
                    finalResult,
                    1,
                    "final",
                    0,
                    finalResult,
                    null,
                    null,
                    null,
                    null,
                    true
            );
        }
        return new RealtimeTranscriptionUpdate(
                finalResult,
                finalResult,
                "",
                finalResult,
                lastUpdate.revision() != null ? lastUpdate.revision() + 1 : 1,
                "final",
                lastUpdate.segmentId(),
                lastUpdate.segmentText(),
                lastUpdate.pgs(),
                lastUpdate.rg(),
                lastUpdate.bg(),
                lastUpdate.ed(),
                true
        );
    }

    /**
     * 构建静态场景（非实时转写）的响应 JSON。
     */
    private static String createStaticResponse(String type, String message, String data) {
        WebSocketResponse response = new WebSocketResponse();
        response.setType(type);
        response.setMessage(message);
        response.setData(data);
        response.setFullText(resolveFullText(type, data));
        response.setIsSnapshot(false);
        response.setUpdateAction(resolveUpdateAction(type));
        response.setTimestamp(System.currentTimeMillis());
        return JSON.toJSONString(response);
    }

    /**
     * 仅转写与最终结果消息返回全文，其他类型返回 null。
     */
    private static String resolveFullText(String type, String data) {
        if ("transcription".equals(type) || "final".equals(type)) {
            return data;
        }
        return null;
    }

    /**
     * 根据消息类型映射前端更新动作：转写为替换、最终结果为归档、其余为无。
     */
    private static String resolveUpdateAction(String type) {
        if ("transcription".equals(type)) {
            return "replace";
        }
        if ("final".equals(type)) {
            return "archive";
        }
        return "none";
    }

    /**
     * WebSocket 响应体，包含消息类型、内容、转写快照及片段范围等信息。
     */
    @Data
    public static class WebSocketResponse {
        /** 消息类型 */
        private String type;
        /** 消息描述 */
        private String message;
        /** 消息数据 */
        private String data;
        /** 完整转写文本 */
        private String fullText;
        /** 展示文本 */
        private String displayText;
        /** 已提交文本 */
        private String committedText;
        /** 实时文本 */
        private String liveText;
        /** 结果版本号 */
        private Integer revision;
        /** 结果状态（partial/final） */
        private String resultStatus;
        /** 是否为快照消息 */
        private Boolean isSnapshot;
        /** 前端更新动作（replace/archive/none） */
        private String updateAction;
        /** 消息时间戳 */
        private Long timestamp;
        /** 片段 id */
        private Integer segmentId;
        /** 句子序号 */
        private Integer sentenceSeq;
        /** 片段文本 */
        private String segmentText;
        /** 动态修正标记 */
        private String pgs;
        /** 替换范围 */
        private int[] rg;
        /** 片段开始时间 */
        private Integer bg;
        /** 片段结束时间 */
        private Integer ed;
        /** 是否为末包 */
        private Boolean isFinalPacket;
    }

    /**
     * WebSocket 客户端控制消息，目前仅包含指令类型。
     */
    @Data
    public static class WebSocketMessage {
        /** 指令类型 */
        private String type;
    }

    /**
     * 单个转写会话的上下文：音频管道流、运行状态与最近一次增量结果。
     */
    private static class TranscriptionSessionContext {
        private final PipedInputStream audioInputStream;
        private final PipedOutputStream audioOutputStream;
        private final AtomicBoolean active;
        private final AtomicBoolean stopRequested = new AtomicBoolean(false);
        private final AtomicReference<RealtimeTranscriptionUpdate> lastUpdate = new AtomicReference<>();

        /**
         * 创建转写会话上下文，绑定音频管道流与活动标记。
         */
        private TranscriptionSessionContext(PipedInputStream audioInputStream,
                                            PipedOutputStream audioOutputStream,
                                            AtomicBoolean active) {
            this.audioInputStream = audioInputStream;
            this.audioOutputStream = audioOutputStream;
            this.active = active;
        }
    }
}
