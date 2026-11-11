package com.banma.interview.media.infrastructure.integration;

import cn.hutool.core.util.StrUtil;
import cn.xfyun.api.IatClient;
import cn.xfyun.model.response.iat.IatResponse;
import cn.xfyun.model.response.iat.IatResult;
import cn.xfyun.model.response.iat.Text;
import cn.xfyun.service.iat.AbstractIatWebSocketListener;
import com.alibaba.fastjson2.JSONArray;
import com.alibaba.fastjson2.JSONObject;
import com.banma.interview.common.config.storage.ApplicationStorageProperties;
import com.banma.interview.common.config.xunfei.XunfeiLatProperties;
import com.banma.interview.common.convention.exception.ClientException;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.apache.commons.codec.binary.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.File;
import java.io.InputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Xunfei speech recognition service.
 * Supports file transcription and real-time large-model transcription.
 * 讯飞语音识别服务：支持音频文件转写以及基于大模型的实时语音转写。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class XunfeiAudioService {

    private static final OkHttpClient WS_CLIENT = new OkHttpClient.Builder()
            .pingInterval(20, TimeUnit.SECONDS)
            .build();

    private static final String AST_WS_BASE_URL = "wss://office-api-ast-dx.iflyaisol.com/ast/communicate/v1";
    private static final int CHUNK_SIZE_BYTES = 1280;

    private final XunfeiLatProperties xunfeiLatPropertiesConfig;
    private final ApplicationStorageProperties storageProperties;
    @Resource(name = "queryExecutor")
    private ExecutorService queryExecutor;

    private IatClient iatClient;

    /**
     * 初始化讯飞 IAT 客户端：使用配置中的凭证签名，并开启动态修正（wpgs）。
     * 初始化失败会抛出运行时异常，阻止服务启动。
     */
    @PostConstruct
    public void init() {
        try {
            // （1）基于配置凭证构建 IAT 客户端并开启动态修正
            iatClient = new IatClient.Builder()
                    .signature(
                            normalize(xunfeiLatPropertiesConfig.getAppId()),
                            normalize(xunfeiLatPropertiesConfig.getApiKey()),
                            normalize(xunfeiLatPropertiesConfig.getApiSecret())
                    )
                    .dwa("wpgs")
                    .build();

            log.info("Xunfei audio service initialized, appId={}, apiKeyLen={}, apiSecretLen={}",
                    mask(normalize(xunfeiLatPropertiesConfig.getAppId())),
                    lengthOf(normalize(xunfeiLatPropertiesConfig.getApiKey())),
                    lengthOf(normalize(xunfeiLatPropertiesConfig.getApiSecret())));
        } catch (Exception ex) {
            // （2）初始化失败时记录错误并中断启动
            log.error("Failed to initialize Xunfei audio service", ex);
            throw new RuntimeException("Failed to initialize Xunfei audio service", ex);
        }
    }

    /**
     * 对上传的音频文件做一次性转写：基于 IAT WebSocket 回调收集片段，等待完成后返回全文。
     *
     * @param audioFile 上传的音频文件
     * @return 转写完成的异步结果
     */
    public CompletableFuture<String> convertAudioToText(MultipartFile audioFile) {
        // （1）准备结果 future、同步闩锁与结果片段容器
        CompletableFuture<String> future = new CompletableFuture<>();
        CountDownLatch latch = new CountDownLatch(1);
        List<Text> resultSegments = new ArrayList<>();

        // （2）注册 IAT WebSocket 监听器，收集转写片段
        AbstractIatWebSocketListener listener = new AbstractIatWebSocketListener() {
            /**
             * 处理 IAT 成功响应：异常码直接失败，正常响应累积片段，末包汇总结果。
             */
            @Override
            public void onSuccess(WebSocket webSocket, IatResponse iatResponse) {
                // （1）响应码非 0 时以异常结束
                if (iatResponse.getCode() != 0) {
                    future.completeExceptionally(new RuntimeException("IAT failed: " + iatResponse.getMessage()));
                    latch.countDown();
                    return;
                }

                // （2）累积结果文本，末包（status=2）时汇总并释放等待
                if (iatResponse.getData() != null && iatResponse.getData().getResult() != null) {
                    IatResult result = iatResponse.getData().getResult();
                    Text textObject = result.getText();
                    if (textObject != null) {
                        handleResultText(textObject, resultSegments);
                    }

                    if (iatResponse.getData().getStatus() == 2) {
                        future.complete(getFinalResult(resultSegments));
                        latch.countDown();
                    }
                }
            }

            /**
             * 处理 IAT 连接失败：以异常结束结果并释放等待。
             */
            @Override
            public void onFail(WebSocket webSocket, Throwable t, Response response) {
                future.completeExceptionally(t);
                latch.countDown();
            }
        };

        try {
            // （3）落临时文件并提交 IAT 转写
            Path tempDir = storageProperties.getAudioTempPath();
            Files.createDirectories(tempDir);

            String fileName = "audio_" + System.currentTimeMillis() + "_" + Thread.currentThread().getId() + ".tmp";
            Path tempFilePath = tempDir.resolve(fileName);
            try (var inputStream = audioFile.getInputStream()) {
                Files.copy(inputStream, tempFilePath, StandardCopyOption.REPLACE_EXISTING);
            }

            File tempFile = tempFilePath.toFile();
            iatClient.send(tempFile, listener);

            // （4）等待回调完成，最多 60 秒，超时则失败
            if (!latch.await(60, TimeUnit.SECONDS) && !future.isDone()) {
                future.completeExceptionally(new RuntimeException("IAT timeout"));
            }
            cleanupTempFile(tempFilePath);
        } catch (Exception ex) {
            // （5）流程异常时以异常结束结果
            future.completeExceptionally(ex);
        }

        return future;
    }

    /**
     * Realtime transcription for the large-model ASR endpoint.
     * Docs: /doc/spark/asr_llm/rtasr_llm.html
     * 使用大模型实时语音识别接口做流式转写，并通过回调持续推送中间结果。
     */
    public CompletableFuture<String> realTimeAudioToText(InputStream audioInputStream, AudioResultCallback callback) {
        CompletableFuture<String> future = new CompletableFuture<>();

        // （1）校验音频输入流是否为空
        if (audioInputStream == null) {
            future.completeExceptionally(new ClientException("audioInputStream cannot be null"));
            return future;
        }

        // （2）读取并校验讯飞凭证配置
        String appId = normalize(xunfeiLatPropertiesConfig.getAppId());
        String apiKey = normalize(xunfeiLatPropertiesConfig.getApiKey());
        String apiSecret = normalize(xunfeiLatPropertiesConfig.getApiSecret());

        if (StrUtil.isBlank(appId) || StrUtil.isBlank(apiKey) || StrUtil.isBlank(apiSecret)) {
            future.completeExceptionally(new RuntimeException(
                    "Large-model realtime ASR requires appId/apiKey/apiSecret"));
            return future;
        }

        // （3）生成会话 id 并构建带签名的 WebSocket 地址
        String sessionId = UUID.randomUUID().toString();
        String wsUrl;
        try {
            wsUrl = buildAstUrl(appId, apiKey, apiSecret, sessionId);
        } catch (Exception ex) {
            future.completeExceptionally(new RuntimeException("Failed to build AST websocket URL", ex));
            return future;
        }

        // （4）初始化结果组装器与各类计数状态
        AstTranscriptionAssembler assembler = new AstTranscriptionAssembler();
        AtomicInteger fallbackSn = new AtomicInteger(0);
        AtomicInteger rawPacketCounter = new AtomicInteger(0);
        AtomicInteger revisionCounter = new AtomicInteger(0);
        StringBuilder latestDisplay = new StringBuilder();
        // （5）建立 WebSocket 连接并注册事件监听器
        Request request = new Request.Builder().url(wsUrl).build();
        WS_CLIENT.newWebSocket(request, new WebSocketListener() {
            /**
             * 连接建立后在查询线程池中异步推送音频流。
             */
            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                CompletableFuture.runAsync(
                        () -> sendAudioStream(webSocket, audioInputStream, sessionId, future),
                        queryExecutor
                );
            }

            /**
             * 处理服务端识别结果：解析文本、合并片段并回调，末包时完成结果并关闭连接。
             */
            @Override
            public void onMessage(WebSocket webSocket, String text) {
                try {
                    // （1）累加原始包序号并记录原始报文
                    int packetNo = rawPacketCounter.incrementAndGet();
                    log.info("[ASR_RAW_PACKET] sessionId={}, packetNo={}, payload={}", sessionId, packetNo, text);
                    JSONObject root = JSONObject.parseObject(text);
                    String action = root.getString("action");
                    // （2）服务端返回 error 动作时直接以异常结束
                    if ("error".equalsIgnoreCase(action)) {
                        String code = root.getString("code");
                        String desc = root.getString("desc");
                        future.completeExceptionally(new RuntimeException(
                                "AST business failure: code=" + code + ", desc=" + desc + ", raw=" + text));
                        return;
                    }

                    // （3）提取状态节点、文本、片段 id 及时间范围等字段
                    JSONObject st = extractAstSt(root);
                    String partialText = extractAstText(root);
                    int segmentId = resolveSegmentId(root, st, fallbackSn);
                    String pgs = st != null ? st.getString("pgs") : null;
                    Integer bg = st != null ? st.getInteger("bg") : null;
                    Integer ed = st != null ? st.getInteger("ed") : null;
                    int[] rg = extractRg(st);
                    boolean finalPacket = isAstFinal(root);
                    // （4）文本非空时合并片段，仅当快照变化时触发回调
                    if (StrUtil.isNotBlank(partialText)) {
                        assembler.apply(segmentId, pgs, rg, bg, ed, partialText, finalPacket);
                        String merged = assembler.buildSnapshot();
                        if (!merged.equals(latestDisplay.toString())) {
                            String committedText = assembler.buildCommittedText(segmentId, finalPacket);
                            String liveText = assembler.buildLiveText(committedText, merged, partialText, finalPacket);
                            latestDisplay.setLength(0);
                            latestDisplay.append(merged);
                            if (callback != null) {
                                callback.onResult(new RealtimeTranscriptionUpdate(
                                        merged,
                                        committedText,
                                        liveText,
                                        merged,
                                        revisionCounter.incrementAndGet(),
                                        finalPacket ? "final" : "partial",
                                        segmentId,
                                        partialText,
                                        pgs,
                                        rg,
                                        bg,
                                        ed,
                                        finalPacket
                                ));
                            }
                        }
                    }

                    // （5）末包时完成最终结果并关闭连接
                    if (finalPacket) {
                        if (!future.isDone()) {
                            String finalText = assembler.buildSnapshot();
                            if (StrUtil.isBlank(finalText)) {
                                finalText = latestDisplay.toString();
                            }
                            future.complete(finalText);
                        }
                        webSocket.close(1000, "completed");
                    }
                } catch (Exception ex) {
                    // （6）解析失败时以异常结束结果
                    if (!future.isDone()) {
                        future.completeExceptionally(new RuntimeException("Failed to parse AST response: " + text, ex));
                    }
                }
            }

            /**
             * 连接失败时以异常结束结果。
             */
            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                if (!future.isDone()) {
                    future.completeExceptionally(new RuntimeException("AST websocket failure", t));
                }
            }

            /**
             * 连接关闭时若结果仍未完成，则用当前快照兜底完成。
             */
            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                if (!future.isDone()) {
                    String finalText = assembler.buildSnapshot();
                    if (StrUtil.isBlank(finalText)) {
                        finalText = latestDisplay.toString();
                    }
                    future.complete(finalText);
                }
            }
        });

        return future;
    }

    /**
     * 从响应报文中逐层提取状态节点 st：优先 data.cn.st，其次 data.st、cn.st、根节点 st。
     */
    private JSONObject extractAstSt(JSONObject root) {
        // （1）根节点为空时直接返回
        if (root == null) {
            return null;
        }

        // （2）按 data.cn.st -> data.st -> cn.st -> 根 st 的顺序回退查找
        JSONObject data = root.getJSONObject("data");
        if (data != null) {
            JSONObject cn = data.getJSONObject("cn");
            if (cn != null && cn.getJSONObject("st") != null) {
                return cn.getJSONObject("st");
            }
            if (data.getJSONObject("st") != null) {
                return data.getJSONObject("st");
            }
        }

        JSONObject cn = root.getJSONObject("cn");
        if (cn != null && cn.getJSONObject("st") != null) {
            return cn.getJSONObject("st");
        }
        return root.getJSONObject("st");
    }

    /**
     * 解析片段 id；报文未提供时使用自增兜底序号。
     */
    private int resolveSegmentId(JSONObject root, JSONObject st, AtomicInteger fallbackSn) {
        // （1）优先使用报文中的片段 id，并同步兜底序号
        Integer segId = extractSegmentId(root, st);
        if (segId != null) {
            fallbackSn.set(Math.max(fallbackSn.get(), segId));
            return segId;
        }
        // （2）报文缺少片段 id 时自增兜底序号
        return fallbackSn.incrementAndGet();
    }

    /**
     * 依次尝试从 data.seg_id、st.seg_id、st.sn 中读取片段 id。
     */
    private Integer extractSegmentId(JSONObject root, JSONObject st) {
        JSONObject data = root != null ? root.getJSONObject("data") : null;
        Integer segId = data != null ? data.getInteger("seg_id") : null;
        if (segId != null) {
            return segId;
        }

        segId = st != null ? st.getInteger("seg_id") : null;
        if (segId != null) {
            return segId;
        }

        return st != null ? st.getInteger("sn") : null;
    }

    /**
     * 解析状态节点中的时间范围 rg，保证返回的起止值有序。
     */
    private int[] extractRg(JSONObject st) {
        if (st == null) {
            return null;
        }
        JSONArray rg = st.getJSONArray("rg");
        if (rg == null || rg.size() < 2) {
            return null;
        }

        Integer start = rg.getInteger(0);
        Integer end = rg.getInteger(1);
        if (start == null || end == null) {
            return null;
        }
        if (start > end) {
            int tmp = start;
            start = end;
            end = tmp;
        }
        return new int[]{start, end};
    }

    /**
     * 按动态修正标记（pgs）合并片段：rpl 先删除被替换范围内的旧片段，再写入当前片段。
     */
    private void applyAstSegment(TreeMap<Integer, SegmentState> sentencePool,
                                 int sn,
                                 String pgs,
                                 int[] rg,
                                 String text,
                                 boolean finalized) {
        if (sentencePool == null || text == null) {
            return;
        }

        // （1）rpl 修正：先删除被替换范围内的旧片段再写入
        if ("rpl".equalsIgnoreCase(pgs)) {
            if (rg != null) {
                for (int i = rg[0]; i <= rg[1]; i++) {
                    sentencePool.remove(i);
                }
            }
            upsertAstSegment(sentencePool, sn, text, finalized);
            return;
        }

        // （2）apd 追加：直接写入当前片段
        if ("apd".equalsIgnoreCase(pgs)) {
            upsertAstSegment(sentencePool, sn, text, finalized);
            return;
        }

        // （3）其他标记按序号覆盖写入
        upsertAstSegment(sentencePool, sn, text, finalized);
    }

    /**
     * 在报文未提供 pgs 时，基于时间范围与文本相似度合并片段，尽量避免重复文本。
     */
    private void applyAstSegmentWithoutPgs(TreeMap<Integer, SegmentState> sentencePool,
                                           int sn,
                                           Integer bg,
                                           Integer ed,
                                           String text,
                                           boolean finalized) {
        if (sentencePool == null || text == null) {
            return;
        }

        // （1）缺少时间范围时直接按序号写入片段
        if (bg == null || ed == null) {
            upsertAstSegment(sentencePool, sn, text, finalized);
            return;
        }

        // （2）同范围且本次仅为标点时，追加到已有文本末尾
        SegmentState sameRange = findExactRangeState(sentencePool, bg, ed);
        if (sameRange != null && isPunctuationOnly(text) && StrUtil.isNotBlank(sameRange.text)) {
            sameRange.text = appendTrailingPunctuation(sameRange.text, text);
            sameRange.finalized = sameRange.finalized || finalized;
            sameRange.updatedAt = System.currentTimeMillis();
            return;
        }

        // （3）存在可复用的同句片段时更新并清理被覆盖片段
        SegmentState reusable = findReusableRangeState(sentencePool, bg, ed, text);
        if (reusable != null) {
            updateSegmentState(reusable, text, finalized, bg, ed);
            removeCoveredSiblingRangeStates(sentencePool, reusable.segId, bg, ed, text);
            return;
        }

        // （4）无复用片段时清理被覆盖片段后新增片段并回填时间范围
        removeCoveredSiblingRangeStates(sentencePool, null, bg, ed, text);
        upsertAstSegment(sentencePool, sn, text, finalized);
        SegmentState state = sentencePool.get(sn);
        if (state != null) {
            state.bg = bg;
            state.ed = ed;
        }
    }

    /**
     * 查找与指定时间范围完全一致的片段状态。
     */
    private SegmentState findExactRangeState(TreeMap<Integer, SegmentState> sentencePool, Integer bg, Integer ed) {
        if (bg == null || ed == null || sentencePool == null || sentencePool.isEmpty()) {
            return null;
        }
        for (SegmentState state : sentencePool.values()) {
            if (state == null || state.bg == null || state.ed == null) {
                continue;
            }
            if (state.bg.equals(bg) && state.ed.equals(ed)) {
                return state;
            }
        }
        return null;
    }

    /**
     * 查找可复用的片段状态：时间范围重叠度足够且文本被判定为同一句的演进版本，取评分最高者。
     */
    private SegmentState findReusableRangeState(TreeMap<Integer, SegmentState> sentencePool,
                                                Integer bg,
                                                Integer ed,
                                                String text) {
        if (bg == null || ed == null || sentencePool == null || sentencePool.isEmpty() || StrUtil.isBlank(text)) {
            return null;
        }

        SegmentState best = null;
        double bestScore = -1D;
        // （1）遍历片段池，跳过无效范围或不重叠的片段
        for (SegmentState state : sentencePool.values()) {
            if (state == null || state.bg == null || state.ed == null) {
                continue;
            }
            if (!isRangeOverlapping(bg, ed, state.bg, state.ed)) {
                continue;
            }
            if (state.bg.equals(bg) && state.ed.equals(ed)) {
                return state;
            }

            // （2）重叠度与文本包含关系加权评分，保留最高分片段
            double overlapRatio = calculateOverlapRatio(bg, ed, state.bg, state.ed);
            if (overlapRatio < 0.6D || !isLikelySameSegmentEvolution(state.text, text)) {
                continue;
            }

            double score = overlapRatio;
            if (containsComparableText(text, state.text)) {
                score += 1D;
            }
            if (score > bestScore) {
                bestScore = score;
                best = state;
            }
        }
        return best;
    }

    /**
     * 移除被当前时间范围完全覆盖、且文本属于同一句演进的其他片段，避免重复展示。
     */
    private void removeCoveredSiblingRangeStates(TreeMap<Integer, SegmentState> sentencePool,
                                                 Integer retainedSegId,
                                                 Integer bg,
                                                 Integer ed,
                                                 String text) {
        if (bg == null || ed == null || sentencePool == null || sentencePool.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<Integer, SegmentState>> iterator = sentencePool.entrySet().iterator();
        // （1）遍历片段池，跳过无效片段与被保留的片段
        while (iterator.hasNext()) {
            Map.Entry<Integer, SegmentState> entry = iterator.next();
            SegmentState state = entry.getValue();
            if (state == null || state.bg == null || state.ed == null) {
                continue;
            }
            if (retainedSegId != null && retainedSegId.equals(entry.getKey())) {
                continue;
            }
            if (isRangeFullyCoveredBy(bg, ed, state.bg, state.ed)
                    && isLikelySameSegmentEvolution(state.text, text)) {
                // （2）删除被完全覆盖且文本同源的片段
                iterator.remove();
            }
        }
    }

    private boolean isRangeOverlapping(int bg1, int ed1, int bg2, int ed2) {
        return bg1 <= ed2 && bg2 <= ed1;
    }

    private boolean isRangeFullyCoveredBy(int outerBg, int outerEd, int innerBg, int innerEd) {
        return outerBg <= innerBg && outerEd >= innerEd;
    }

    /**
     * 计算两个时间区间的重叠比例，以较小跨度作为分母。
     */
    private double calculateOverlapRatio(int bg1, int ed1, int bg2, int ed2) {
        int overlapStart = Math.max(bg1, bg2);
        int overlapEnd = Math.min(ed1, ed2);
        int overlap = overlapEnd - overlapStart;
        if (overlap <= 0) {
            return 0D;
        }
        int span1 = Math.max(1, ed1 - bg1);
        int span2 = Math.max(1, ed2 - bg2);
        return (double) overlap / Math.min(span1, span2);
    }

    /**
     * 判断文本是否只包含标点与空白，不含任何字母、数字或汉字。
     */
    private boolean isPunctuationOnly(String text) {
        if (StrUtil.isBlank(text)) {
            return false;
        }
        String trimmed = rtrim(text);
        if (trimmed.isEmpty()) {
            return false;
        }
        for (int i = 0; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
            if (Character.isWhitespace(ch)) {
                continue;
            }
            if (Character.isLetterOrDigit(ch) || Character.UnicodeScript.of(ch) == Character.UnicodeScript.HAN) {
                return false;
            }
        }
        return true;
    }

    /**
     * 在基础文本末尾追加标点，若已以该标点结尾则不重复追加。
     */
    private String appendTrailingPunctuation(String baseText, String punctuation) {
        String base = rtrim(baseText);
        String suffix = rtrim(punctuation);
        if (suffix.isEmpty()) {
            return base;
        }
        if (base.endsWith(suffix)) {
            return base;
        }
        return base + suffix;
    }

    /**
     * 去除文本末尾的空白字符。
     */
    private String rtrim(String text) {
        if (text == null) {
            return "";
        }
        int end = text.length();
        while (end > 0 && Character.isWhitespace(text.charAt(end - 1))) {
            end--;
        }
        return text.substring(0, end);
    }

    /**
     * 判断两段文本是否可能属于同一句的演进结果：相等、互相包含或公共前缀占比不低于 80%。
     */
    private boolean isLikelySameSegmentEvolution(String existingText, String incomingText) {
        String existingComparable = toComparableText(existingText);
        String incomingComparable = toComparableText(incomingText);
        if (existingComparable.isEmpty() || incomingComparable.isEmpty()) {
            return false;
        }
        if (existingComparable.equals(incomingComparable)) {
            return true;
        }
        if (incomingComparable.contains(existingComparable) || existingComparable.contains(incomingComparable)) {
            return true;
        }
        int commonPrefix = commonPrefixLength(existingComparable, incomingComparable);
        int minLength = Math.min(existingComparable.length(), incomingComparable.length());
        return minLength > 0 && ((double) commonPrefix / minLength) >= 0.8D;
    }

    /**
     * 判断去除标点后的源文本是否包含候选文本。
     */
    private boolean containsComparableText(String source, String candidate) {
        String sourceComparable = toComparableText(source);
        String candidateComparable = toComparableText(candidate);
        if (sourceComparable.isEmpty() || candidateComparable.isEmpty()) {
            return false;
        }
        return sourceComparable.contains(candidateComparable);
    }

    /**
     * 将文本归一化为仅保留字母、数字与汉字的可比较字符串。
     */
    private String toComparableText(String text) {
        if (text == null) {
            return "";
        }
        StringBuilder builder = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isLetterOrDigit(ch) || Character.UnicodeScript.of(ch) == Character.UnicodeScript.HAN) {
                builder.append(ch);
            }
        }
        return builder.toString();
    }

    /**
     * 计算两段文本的公共前缀长度。
     */
    private int commonPrefixLength(String left, String right) {
        int limit = Math.min(left.length(), right.length());
        int index = 0;
        while (index < limit && left.charAt(index) == right.charAt(index)) {
            index++;
        }
        return index;
    }

    /**
     * 更新已有片段状态的文本、完成标记、时间范围与更新时间。
     */
    private void updateSegmentState(SegmentState state,
                                    String text,
                                    boolean finalized,
                                    Integer bg,
                                    Integer ed) {
        if (state == null) {
            return;
        }
        state.text = text;
        state.finalized = state.finalized || finalized;
        state.bg = bg;
        state.ed = ed;
        state.updatedAt = System.currentTimeMillis();
    }

    /**
     * 新增或更新指定序号的片段状态，完成标记只会从 false 变为 true。
     */
    private void upsertAstSegment(TreeMap<Integer, SegmentState> sentencePool,
                                  int sn,
                                  String text,
                                  boolean finalized) {
        SegmentState state = sentencePool.get(sn);
        if (state == null) {
            state = new SegmentState(sn);
        }
        state.text = text;
        state.finalized = state.finalized || finalized;
        state.updatedAt = System.currentTimeMillis();
        sentencePool.put(sn, state);
    }

    /**
     * 按片段序号顺序拼接所有非空片段文本，得到完整结果。
     */
    private String buildFinalResult(Map<Integer, SegmentState> sentencePool) {
        if (sentencePool == null || sentencePool.isEmpty()) {
            return "";
        }
        StringBuilder result = new StringBuilder();
        for (SegmentState part : sentencePool.values()) {
            if (part != null && part.text != null) {
                result.append(part.text);
            }
        }
        return result.toString();
    }

    /**
     * 按 1280 字节分块从输入流读取音频并推送到 WebSocket，发送结束后发送结束标记。
     */
    private void sendAudioStream(WebSocket webSocket,
                                 InputStream audioInputStream,
                                 String sessionId,
                                 CompletableFuture<String> future) {
        byte[] buffer = new byte[CHUNK_SIZE_BYTES];
        try (InputStream in = audioInputStream) {
            // （1）循环读取音频分块并按固定间隔发送
            int read;
            while ((read = in.read(buffer)) != -1) {
                byte[] chunk = read == buffer.length ? buffer : copyOf(buffer, read);
                boolean ok = webSocket.send(okio.ByteString.of(chunk, 0, chunk.length));
                if (!ok) {
                    throw new RuntimeException("AST websocket send returned false");
                }
                Thread.sleep(40);
            }
            // （2）全部发送完成后告知服务端音频结束
            webSocket.send("{\"end\":true,\"sessionId\":\"" + sessionId + "\"}");
        } catch (Exception ex) {
            // （3）发送异常时以异常结束结果并关闭连接
            if (!future.isDone()) {
                future.completeExceptionally(new RuntimeException("Failed to send AST audio stream", ex));
            }
            webSocket.close(1011, "send failed");
        }
    }

    /**
     * 构建大模型实时转写接口的带签名 WebSocket 地址。
     */
    private String buildAstUrl(String appId, String apiKey, String apiSecret, String sessionId) throws Exception {
        // （1）组装签名所需的公共参数
        TreeMap<String, String> params = new TreeMap<>();
        params.put("appId", appId);
        params.put("accessKeyId", apiKey);
        params.put("audio_encode", "pcm_s16le");
        params.put("lang", "autodialect");
        params.put("samplerate", "16000");
        params.put("sessionId", sessionId);
        params.put("utc", OffsetDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ssZ")));
        params.put("uuid", UUID.randomUUID().toString().replace("-", ""));

        // （2）对规范化查询串做 HMAC-SHA1 签名并拼出完整地址
        String signTarget = buildCanonicalQuery(params);
        String signature = hmacSha1Base64(signTarget, apiSecret);
        params.put("signature", signature);

        return AST_WS_BASE_URL + "?" + buildCanonicalQuery(params);
    }

    /**
     * 按参数名有序拼接 URL 查询串，键值均做 URL 编码。
     */
    private String buildCanonicalQuery(Map<String, String> params) {
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (Map.Entry<String, String> e : params.entrySet()) {
            if (!first) {
                sb.append("&");
            }
            first = false;
            sb.append(urlEncode(e.getKey())).append("=").append(urlEncode(e.getValue()));
        }
        return sb.toString();
    }

    /**
     * 对字符串做 UTF-8 URL 编码，编码失败时抛出运行时异常。
     */
    private String urlEncode(String value) {
        try {
            return URLEncoder.encode(value, StandardCharsets.UTF_8.name());
        } catch (Exception ex) {
            throw new RuntimeException("URL encode failed", ex);
        }
    }

    /**
     * 使用 HMAC-SHA1 计算签名并做 Base64 编码。
     */
    private String hmacSha1Base64(String content, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA1");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
        byte[] signBytes = mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signBytes);
    }

    private byte[] copyOf(byte[] src, int len) {
        byte[] dst = new byte[len];
        System.arraycopy(src, 0, dst, 0, len);
        return dst;
    }

    /**
     * 从状态节点的 rt -> ws -> cw 层级中提取本次识别出的文本。
     */
    private String extractAstText(JSONObject root) {
        // （1）定位状态节点及其第一个识别结果
        JSONObject st = extractAstSt(root);
        if (st == null) {
            return "";
        }
        JSONArray rtArr = st.getJSONArray("rt");
        if (rtArr == null) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        JSONObject rt = rtArr.getJSONObject(0);
        if (rt == null) {
            return "";
        }

        JSONArray wsArr = rt.getJSONArray("ws");
        if (wsArr == null) {
            return "";
        }

        for (int j = 0; j < wsArr.size(); j++) {
            JSONObject ws = wsArr.getJSONObject(j);
            JSONArray cwArr = ws.getJSONArray("cw");
            if (cwArr == null || cwArr.isEmpty()) {
                continue;
            }

            JSONObject cw = cwArr.getJSONObject(0);
            if (cw == null) {
                continue;
            }
            String w = cw.getString("w");
            if (w != null) {
                sb.append(w);
            }
        }
        return sb.toString();
    }

    /**
     * 判断报文是否为末包：data.ls 或状态节点 ls 为 true。
     */
    private boolean isAstFinal(JSONObject root) {
        JSONObject data = root.getJSONObject("data");
        if (data != null && Boolean.TRUE.equals(data.getBoolean("ls"))) {
            return true;
        }
        JSONObject st = extractAstSt(root);
        return st != null && Boolean.TRUE.equals(st.getBoolean("ls"));
    }

    /**
     * 实时转写结果组装器：维护按片段序号排序的片段池，并生成展示快照、已提交文本与实时文本。
     */
    private final class AstTranscriptionAssembler {
        private final TreeMap<Integer, SegmentState> segments = new TreeMap<>();

        /**
         * 根据是否存在 pgs 选择对应的片段合并策略。
         */
        private void apply(int segmentId,
                           String pgs,
                           int[] rg,
                           Integer bg,
                           Integer ed,
                           String text,
                           boolean finalized) {
            if (StrUtil.isBlank(pgs)) {
                applyAstSegmentWithoutPgs(segments, segmentId, bg, ed, text, finalized);
                return;
            }
            applyAstSegment(segments, segmentId, pgs, rg, text, finalized);
        }

        /**
         * 生成当前片段池的完整文本快照。
         */
        private String buildSnapshot() {
            return buildFinalResult(segments);
        }

        /**
         * 生成已提交文本：末包返回完整快照，否则拼接已完成或早于当前片段的文本。
         */
        private String buildCommittedText(int activeSegmentId, boolean finalPacket) {
            // （1）末包直接返回完整快照
            if (finalPacket) {
                return buildSnapshot();
            }
            if (segments.isEmpty()) {
                return "";
            }

            StringBuilder committed = new StringBuilder();
            for (SegmentState segment : segments.values()) {
                if (segment == null || segment.text == null) {
                    continue;
                }
                if (segment.segId < activeSegmentId || segment.finalized) {
                    committed.append(segment.text);
                }
            }
            return committed.toString();
        }

        /**
         * 生成实时文本：从展示快照中去掉已提交部分，无法匹配时回退到当前片段文本。
         */
        private String buildLiveText(String committedText,
                                     String displayText,
                                     String segmentText,
                                     boolean finalPacket) {
            if (finalPacket) {
                return "";
            }
            if (StrUtil.isBlank(displayText)) {
                return "";
            }
            String committed = committedText != null ? committedText : "";
            if (!committed.isEmpty() && displayText.startsWith(committed)) {
                return displayText.substring(committed.length());
            }
            return segmentText != null ? segmentText : displayText;
        }
    }

    /**
     * 转写片段状态：记录片段序号、文本、是否已完成、时间范围及更新时间。
     */
    private static final class SegmentState {
        private final int segId;
        private String text;
        private boolean finalized;
        private Integer bg;
        private Integer ed;
        private long updatedAt;

        private SegmentState(int segId) {
            this.segId = segId;
        }
    }

    /**
     * 处理单个识别文本片段：动态修正（rpl）时先标记被替换的旧片段为删除，再追加新片段。
     */
    private static void handleResultText(Text textObject, List<Text> resultSegments) {
        // （1）rpl 修正时将被替换范围内的旧片段标记为删除
        if (StringUtils.equals(textObject.getPgs(), "rpl")
                && textObject.getRg() != null
                && textObject.getRg().length == 2) {
            int start = textObject.getRg()[0] - 1;
            int end = textObject.getRg()[1] - 1;
            for (int i = start; i <= end && i < resultSegments.size(); i++) {
                resultSegments.get(i).setDeleted(true);
            }
        }
        resultSegments.add(textObject);
    }

    /**
     * 拼接所有未被删除的片段文本作为最终结果。
     */
    private static String getFinalResult(List<Text> resultSegments) {
        StringBuilder finalResult = new StringBuilder();
        for (Text text : resultSegments) {
            if (text != null && !text.isDeleted()) {
                finalResult.append(text.getText());
            }
        }
        return finalResult.toString();
    }

    private String normalize(String value) {
        return value == null ? null : value.trim();
    }

    private int lengthOf(String value) {
        return value == null ? 0 : value.length();
    }

    /**
     * 对凭证做脱敏展示，仅保留首尾各两个字符。
     */
    private String mask(String value) {
        if (StrUtil.isBlank(value)) {
            return "null";
        }
        if (value.length() <= 4) {
            return "****";
        }
        return value.substring(0, 2) + "****" + value.substring(value.length() - 2);
    }

    /**
     * 删除转写临时文件，失败时标记为 JVM 退出时删除并记录告警。
     */
    private void cleanupTempFile(Path tempFile) {
        if (tempFile == null || !Files.exists(tempFile)) {
            return;
        }
        try {
            Files.delete(tempFile);
        } catch (Exception ex) {
            tempFile.toFile().deleteOnExit();
            log.warn("Failed to delete temp file: {}", tempFile);
        }
    }

    /**
     * 实时转写结果回调接口。
     */
    public interface AudioResultCallback {
        /**
         * 每产生一次新的转写快照时回调。
         */
        void onResult(RealtimeTranscriptionUpdate result);
    }

    /**
     * 实时转写增量结果：包含全文、已提交文本、实时文本以及片段级元数据。
     */
    public record RealtimeTranscriptionUpdate(String fullText,
                                              String committedText,
                                              String liveText,
                                              String displayText,
                                              Integer revision,
                                              String resultStatus,
                                              Integer segmentId,
                                              String segmentText,
                                              String pgs,
                                              int[] rg,
                                              Integer bg,
                                              Integer ed,
                                              boolean finalPacket) {
    }
}
