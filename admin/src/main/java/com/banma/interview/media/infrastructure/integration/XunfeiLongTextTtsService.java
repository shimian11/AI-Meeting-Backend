package com.banma.interview.media.infrastructure.integration;

import cn.hutool.core.util.StrUtil;
import com.alibaba.fastjson2.JSONObject;
import com.banma.interview.common.config.xunfei.XunfeiLatProperties;
import com.banma.interview.common.convention.exception.ClientException;
import com.banma.interview.common.convention.exception.ServiceException;
import com.banma.interview.media.api.io.req.LongTextTtsReqDTO;
import com.banma.interview.media.api.io.resp.LongTextTtsTaskRespDTO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * Xunfei long-text TTS service based on the HTTP API.
 * Docs: https://www.xfyun.cn/doc/tts/long_text_tts/API.html
 * 讯飞长文本语音合成服务：基于 HTTP 接口完成任务创建、状态查询与产物下载。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class XunfeiLongTextTtsService {

    private static final int MAX_TEXT_LENGTH = 100000;
    private static final DateTimeFormatter HTTP_DATE_FORMATTER =
            DateTimeFormatter.ofPattern("EEE, dd MMM yyyy HH:mm:ss 'GMT'", Locale.US)
                    .withZone(ZoneOffset.UTC);
    private static final String HOST = "api-dx.xf-yun.com";
    private static final String SCHEME = "https";
    private static final String DTS_CREATE_PATH = "/v1/private/dts_create";
    private static final String DTS_QUERY_PATH = "/v1/private/dts_query";
    private static final MediaType JSON_MEDIA_TYPE = MediaType.parse("application/json; charset=utf-8");
    private static final OkHttpClient HTTP_CLIENT = new OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build();

    private final XunfeiLatProperties xunfeiLatProperties;

    /**
     * 创建长文本语音合成任务，返回任务 id 及初始状态。
     */
    public LongTextTtsTaskRespDTO createTask(LongTextTtsReqDTO requestParam) {
        // （1）校验凭证与创建参数
        validateCredentials();
        validateCreateRequest(requestParam);

        // （2）构建请求体并调用创建任务接口
        JSONObject body = buildCreateTaskBody(requestParam);
        JSONObject response = postWithSign(DTS_CREATE_PATH, body);
        LongTextTtsTaskRespDTO result = parseTaskResponse(response);
        // （3）校验业务码与任务 id
        if (!Integer.valueOf(0).equals(result.getCode())) {
            throw new ServiceException("Failed to create TTS task: " + result.getMessage());
        }
        if (StrUtil.isBlank(result.getTaskId())) {
            throw new ServiceException("TTS task was created but taskId is missing");
        }
        // （4）标记任务创建成功且尚未完成
        result.setSuccess(true);
        result.setCompleted(false);
        return result;
    }

    /**
     * 查询指定语音合成任务的执行状态，成功后下载音频与韵律文件。
     */
    public LongTextTtsTaskRespDTO queryTask(String taskId) {
        // （1）校验凭证与任务 id
        validateCredentials();
        if (StrUtil.isBlank(taskId)) {
            throw new ClientException("taskId must not be blank");
        }

        // （2）组装查询请求并调用查询接口
        JSONObject body = new JSONObject();
        JSONObject header = new JSONObject();
        header.put("app_id", normalize(xunfeiLatProperties.getAppId()));
        header.put("task_id", taskId.trim());
        body.put("header", header);

        JSONObject response = postWithSign(DTS_QUERY_PATH, body);
        LongTextTtsTaskRespDTO result = parseTaskResponse(response);
        // （3）校验业务码并下载任务产物
        if (!Integer.valueOf(0).equals(result.getCode())) {
            throw new ServiceException("Failed to query TTS task: " + result.getMessage());
        }
        enrichDownloadedArtifacts(result);
        return result;
    }

    /**
     * 创建合成任务后轮询等待完成，成功时返回带音频与韵律内容的最终结果。
     */
    public LongTextTtsTaskRespDTO synthesizeAndWait(LongTextTtsReqDTO requestParam) {
        // （1）创建任务并获取任务 id
        LongTextTtsTaskRespDTO created = createTask(requestParam);
        String taskId = created.getTaskId();
        if (StrUtil.isBlank(taskId)) {
            throw new ServiceException("taskId is missing after TTS task creation");
        }

        // （2）计算超时时间与轮询间隔
        int timeoutSeconds = requestParam != null && requestParam.getTimeoutSeconds() != null
                ? Math.max(10, requestParam.getTimeoutSeconds())
                : 90;
        int pollIntervalMs = requestParam != null && requestParam.getPollIntervalMs() != null
                ? Math.max(500, requestParam.getPollIntervalMs())
                : 1500;

        long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
        LongTextTtsTaskRespDTO latest = created;
        // （3）在截止时间前轮询任务状态
        while (System.currentTimeMillis() < deadline) {
            latest = queryTask(taskId);
            String status = latest.getTaskStatus();
            // 状态 5 表示合成成功
            if ("5".equals(status)) {
                latest.setCompleted(true);
                latest.setSuccess(true);
                enrichDownloadedArtifacts(latest);
                return latest;
            }
            // 状态 2/4 表示任务失败
            if ("2".equals(status) || "4".equals(status)) {
                latest.setCompleted(true);
                latest.setSuccess(false);
                throw new ServiceException("TTS task failed, taskId=" + taskId + ", status=" + status
                        + ", message=" + latest.getMessage());
            }
            sleepQuietly(pollIntervalMs);
        }
        // （4）超时仍未完成时抛出异常
        throw new ServiceException("Timed out waiting for TTS task, taskId=" + taskId
                + ", timeoutSeconds=" + timeoutSeconds);
    }

    /**
     * 校验创建任务的请求参数：文本长度以及语速、音量、音调、韵律标记的取值范围。
     */
    private void validateCreateRequest(LongTextTtsReqDTO requestParam) {
        // （1）校验文本非空且不超过最大长度
        if (requestParam == null || StrUtil.isBlank(requestParam.getText())) {
            throw new ClientException("text must not be blank");
        }
        if (requestParam.getText().length() > MAX_TEXT_LENGTH) {
            throw new ClientException("text length must not exceed " + MAX_TEXT_LENGTH + " characters");
        }
        // （2）校验数值参数范围与韵律标记取值
        validateRange("speed", requestParam.getSpeed(), 0, 100);
        validateRange("volume", requestParam.getVolume(), 0, 100);
        validateRange("pitch", requestParam.getPitch(), 0, 100);
        validateBinary("rhy", requestParam.getRhy());
    }

    /**
     * 校验讯飞凭证 appId/apiKey/apiSecret 是否均已配置。
     */
    private void validateCredentials() {
        String appId = normalize(xunfeiLatProperties.getAppId());
        String apiKey = normalize(xunfeiLatProperties.getApiKey());
        String apiSecret = normalize(xunfeiLatProperties.getApiSecret());
        if (StrUtil.hasBlank(appId, apiKey, apiSecret)) {
            throw new ServiceException("Xunfei credentials are missing: appId/apiKey/apiSecret");
        }
    }

    /**
     * 组装创建任务的请求体：header、合成参数与 base64 文本载荷。
     */
    private JSONObject buildCreateTaskBody(LongTextTtsReqDTO requestParam) {
        JSONObject body = new JSONObject();

        // （1）填充 header 中的应用 id
        JSONObject header = new JSONObject();
        header.put("app_id", normalize(xunfeiLatProperties.getAppId()));
        body.put("header", header);

        // （2）填充合成参数（发音人、语速、音量、音调、音频格式等）
        JSONObject parameter = new JSONObject();
        JSONObject dts = new JSONObject();
        dts.put("vcn", defaultIfBlank(requestParam.getVcn(), "x4_mingge"));
        dts.put("language", defaultIfBlank(requestParam.getLanguage(), "zh"));
        dts.put("speed", defaultIfNull(requestParam.getSpeed(), 50));
        dts.put("volume", defaultIfNull(requestParam.getVolume(), 50));
        dts.put("pitch", defaultIfNull(requestParam.getPitch(), 50));
        dts.put("rhy", defaultIfNull(requestParam.getRhy(), 0));
        dts.put("audio", buildAudioConfig(requestParam));
        dts.put("pybuf", buildPybufConfig());
        parameter.put("dts", dts);
        body.put("parameter", parameter);

        // （3）将文本以 UTF-8 Base64 编码后放入 payload
        JSONObject payload = new JSONObject();
        JSONObject textNode = new JSONObject();
        textNode.put("encoding", "utf8");
        textNode.put("compress", "raw");
        textNode.put("format", "plain");
        textNode.put("text", Base64.getEncoder().encodeToString(requestParam.getText().getBytes(StandardCharsets.UTF_8)));
        payload.put("text", textNode);
        body.put("payload", payload);

        return body;
    }

    /**
     * 构建音频输出配置：编码格式与采样率。
     */
    private JSONObject buildAudioConfig(LongTextTtsReqDTO requestParam) {
        JSONObject audio = new JSONObject();
        audio.put("encoding", defaultIfBlank(requestParam.getAudioEncoding(), "lame"));
        audio.put("sample_rate", defaultIfNull(requestParam.getSampleRate(), 16000));
        return audio;
    }

    /**
     * 构建韵律文件输出配置（固定 utf8 + raw + plain）。
     */
    private JSONObject buildPybufConfig() {
        JSONObject pybuf = new JSONObject();
        pybuf.put("encoding", "utf8");
        pybuf.put("compress", "raw");
        pybuf.put("format", "plain");
        return pybuf;
    }

    /**
     * 携带签名发起 HTTP POST 请求并返回解析后的 JSON 响应。
     */
    private JSONObject postWithSign(String path, JSONObject body) {
        // （1）生成带签名的请求信息
        SignedRequestInfo signedRequestInfo = buildSignedRequestInfo(path);
        String requestBody = body.toJSONString();
        log.info("Xunfei TTS signed request, path={}, date={}, url={}, authorizationPrefix={}",
                path,
                signedRequestInfo.getDate(),
                signedRequestInfo.getUrl(),
                signedRequestInfo.getAuthorizationPrefix());

        Request request = new Request.Builder()
                .url(signedRequestInfo.getUrl())
                .post(RequestBody.create(requestBody, JSON_MEDIA_TYPE))
                .addHeader("Host", HOST)
                .addHeader("Date", signedRequestInfo.getDate())
                .addHeader("x-date", signedRequestInfo.getDate())
                .addHeader("Authorization", signedRequestInfo.getAuthorization())
                .addHeader("Content-Type", "application/json")
                .build();

        try (Response response = HTTP_CLIENT.newCall(request).execute()) {
            // （2）校验 HTTP 状态与响应体非空
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                log.error("Xunfei TTS HTTP failed, path={}, status={}, responseDate={}, body={}",
                        path, response.code(), response.header("Date"), responseBody);
                throw new ServiceException("Xunfei TTS request failed, HTTP status: " + response.code());
            }
            if (StrUtil.isBlank(responseBody)) {
                throw new ServiceException("Xunfei TTS response is empty");
            }
            // （3）解析并返回 JSON 响应
            return JSONObject.parseObject(responseBody);
        } catch (Exception ex) {
            throw new ServiceException("Failed to call Xunfei TTS: " + ex.getMessage());
        }
    }

    /**
     * 构建讯飞 HTTP 接口的签名请求信息：日期、HMAC-SHA256 签名及带鉴权参数的 URL。
     */
    private SignedRequestInfo buildSignedRequestInfo(String path) {
        try {
            // （1）拼接待签名原文 host + date + request-line
            String host = HOST;
            String date = HTTP_DATE_FORMATTER.format(Instant.now());
            String requestLine = "POST " + path + " HTTP/1.1";
            String signatureOrigin = "host: " + host + "\n" + "date: " + date + "\n" + requestLine;

            // （2）计算 HMAC-SHA256 签名并组装鉴权串
            String signature = hmacSha256Base64(signatureOrigin, normalize(xunfeiLatProperties.getApiSecret()));
            String authorizationOrigin = String.format(
                    "api_key=\"%s\", algorithm=\"hmac-sha256\", headers=\"host date request-line\", signature=\"%s\"",
                    normalize(xunfeiLatProperties.getApiKey()), signature);
            String authorization = Base64.getEncoder()
                    .encodeToString(authorizationOrigin.getBytes(StandardCharsets.UTF_8));

            // （3）拼装带鉴权参数的请求地址并返回
            HttpUrl url = new HttpUrl.Builder()
                    .scheme(SCHEME)
                    .host(host)
                    .encodedPath(path)
                    .addQueryParameter("host", host)
                    .addQueryParameter("date", date)
                    .addQueryParameter("authorization", authorization)
                    .build();
            return new SignedRequestInfo(url.toString(), date, authorization);
        } catch (Exception ex) {
            throw new ServiceException("Failed to build Xunfei TTS auth URL: " + ex.getMessage());
        }
    }

    /**
     * 使用 HMAC-SHA256 计算签名并做 Base64 编码。
     */
    private String hmacSha256Base64(String content, String secret) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] signBytes = mac.doFinal(content.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signBytes);
    }

    /**
     * 解析讯飞响应为任务结果：映射 header 状态与 payload 中的文件地址，并推导完成/成功标记。
     */
    private LongTextTtsTaskRespDTO parseTaskResponse(JSONObject response) {
        LongTextTtsTaskRespDTO result = new LongTextTtsTaskRespDTO();
        // （1）响应为空时返回失败结果
        if (response == null) {
            result.setCode(-1);
            result.setMessage("empty response");
            result.setCompleted(true);
            result.setSuccess(false);
            return result;
        }

        // （2）解析 header 中的业务码、sid、任务 id 与任务状态
        JSONObject header = response.getJSONObject("header");
        result.setCode(header != null ? header.getInteger("code") : -1);
        result.setMessage(header != null ? header.getString("message") : "unknown");
        result.setSid(header != null ? header.getString("sid") : null);
        result.setTaskId(header != null ? header.getString("task_id") : null);
        result.setTaskStatus(header != null && header.get("task_status") != null
                ? String.valueOf(header.get("task_status"))
                : null);

        // （3）解析 payload 中的音频与韵律文件地址
        JSONObject payload = response.getJSONObject("payload");
        JSONObject audio = payload != null ? payload.getJSONObject("audio") : null;
        if (audio != null) {
            result.setAudioUrl(tryDecodeBase64Url(audio.getString("audio")));
        }
        JSONObject pybuf = payload != null ? payload.getJSONObject("pybuf") : null;
        if (pybuf != null) {
            result.setPybufUrl(tryDecodeBase64Url(pybuf.getString("text")));
        }

        // （4）根据任务状态推导完成与成功标记
        result.setCompleted("5".equals(result.getTaskStatus()) || "2".equals(result.getTaskStatus())
                || "4".equals(result.getTaskStatus()));
        result.setSuccess(Integer.valueOf(0).equals(result.getCode()) && "5".equals(result.getTaskStatus()));
        return result;
    }

    /**
     * 对成功且已完成的任务下载音频与韵律文件，填充到结果对象中。
     */
    private void enrichDownloadedArtifacts(LongTextTtsTaskRespDTO result) {
        // （1）仅处理成功且已完成的任务
        if (result == null || !Boolean.TRUE.equals(result.getSuccess()) || !Boolean.TRUE.equals(result.getCompleted())) {
            return;
        }
        // （2）按需下载音频并转为 Base64
        if (StrUtil.isNotBlank(result.getAudioUrl()) && StrUtil.isBlank(result.getAudioBase64())) {
            result.setAudioBase64(downloadBinaryAsBase64(result.getAudioUrl()));
        }
        // （3）按需下载韵律文本内容
        if (StrUtil.isNotBlank(result.getPybufUrl()) && StrUtil.isBlank(result.getPybufContent())) {
            result.setPybufContent(downloadTextContent(result.getPybufUrl()));
        }
    }

    /**
     * 下载二进制文件并返回其 Base64 编码字符串。
     */
    private String downloadBinaryAsBase64(String url) {
        // （1）发起 GET 下载请求
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = HTTP_CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new ServiceException("Failed to download TTS audio, HTTP status: " + response.code());
            }
            if (response.body() == null) {
                throw new ServiceException("Failed to download TTS audio: response body is empty");
            }
            byte[] bytes = response.body().bytes();
            if (bytes.length == 0) {
                throw new ServiceException("Failed to download TTS audio: content is empty");
            }
            // （2）将音频字节编码为 Base64 返回
            return Base64.getEncoder().encodeToString(bytes);
        } catch (ServiceException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ServiceException("Failed to download TTS audio: " + ex.getMessage());
        }
    }

    /**
     * 下载文本文件并返回其内容。
     */
    private String downloadTextContent(String url) {
        Request request = new Request.Builder().url(url).get().build();
        try (Response response = HTTP_CLIENT.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new ServiceException("Failed to download TTS pybuf, HTTP status: " + response.code());
            }
            if (response.body() == null) {
                throw new ServiceException("Failed to download TTS pybuf: response body is empty");
            }
            String content = response.body().string();
            if (StrUtil.isBlank(content)) {
                throw new ServiceException("Failed to download TTS pybuf: content is empty");
            }
            return content;
        } catch (ServiceException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new ServiceException("Failed to download TTS pybuf: " + ex.getMessage());
        }
    }

    /**
     * 尝试将 Base64 编码的地址解码为原文，解码失败时原样返回。
     */
    private String tryDecodeBase64Url(String value) {
        if (StrUtil.isBlank(value)) {
            return null;
        }
        try {
            return new String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8);
        } catch (Exception ex) {
            return value;
        }
    }

    /**
     * 轮询等待指定时长，被中断时恢复中断标记并抛出业务异常。
     */
    private void sleepQuietly(long sleepMillis) {
        try {
            Thread.sleep(sleepMillis);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new ServiceException("Polling for TTS task was interrupted");
        }
    }

    private String normalize(String value) {
        return value == null ? null : value.trim();
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return StrUtil.isBlank(value) ? defaultValue : value.trim();
    }

    private Integer defaultIfNull(Integer value, Integer defaultValue) {
        return value == null ? defaultValue : value;
    }

    /**
     * 签名请求信息：包含最终请求 URL、日期与鉴权串。
     */
    private static final class SignedRequestInfo {
        private final String url;
        private final String date;
        private final String authorization;

        private SignedRequestInfo(String url, String date, String authorization) {
            this.url = url;
            this.date = date;
            this.authorization = authorization;
        }

        private String getUrl() {
            return url;
        }

        private String getDate() {
            return date;
        }

        private String getAuthorization() {
            return authorization;
        }

        private String getAuthorizationPrefix() {
            if (authorization == null) {
                return null;
            }
            return authorization.length() <= 24 ? authorization : authorization.substring(0, 24);
        }
    }

    /**
     * 校验数值参数是否落在闭区间内，为 null 时跳过。
     */
    private void validateRange(String fieldName, Integer value, int minInclusive, int maxInclusive) {
        if (value == null) {
            return;
        }
        if (value < minInclusive || value > maxInclusive) {
            throw new ClientException(fieldName + " must be between " + minInclusive + " and " + maxInclusive);
        }
    }

    /**
     * 校验参数是否为 0/1 二值，为 null 时跳过。
     */
    private void validateBinary(String fieldName, Integer value) {
        if (value == null) {
            return;
        }
        if (value != 0 && value != 1) {
            throw new ClientException(fieldName + " must be 0 or 1");
        }
    }
}
