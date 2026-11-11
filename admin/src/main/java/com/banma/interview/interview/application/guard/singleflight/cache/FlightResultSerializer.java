package com.banma.interview.interview.application.guard.singleflight.cache;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import com.banma.interview.interview.application.guard.singleflight.model.FlightStoredResult;
import com.banma.interview.interview.config.InterviewAiSingleFlightConfiguration;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * AI 结果序列化组件，负责对结果进行压缩、编码、校验摘要生成与反序列化恢复，
 * 以便安全地写入 Redis 并控制存储成本。
 */
@Component
public class FlightResultSerializer {

    /**
     * 将 AI 结果按阶段策略压缩、Base64 编码并生成校验摘要，封装为可写入 Redis 的结构。
     *
     * @param value 原始结果内容，null 按空串处理
     * @param ownerToken owner 节点令牌
     * @param policy 阶段策略，控制压缩阈值与算法
     * @return 可持久化的结果对象
     */
    public FlightStoredResult serialize(String value, Long ownerToken,
                                        InterviewAiSingleFlightConfiguration.StageFlightPolicy policy) {
        // （1）原始内容转 UTF-8 字节，并解析压缩阈值与算法
        String safeValue = value == null ? "" : value;
        byte[] rawBytes = safeValue.getBytes(StandardCharsets.UTF_8);
        int threshold = policy == null || policy.getCompressionThresholdBytes() == null
                ? Integer.MAX_VALUE
                : Math.max(0, policy.getCompressionThresholdBytes());
        String codec = normalizeCodec(policy == null ? null : policy.getCompressionCodec());
        // （2）达到阈值且算法可用时执行 gzip 压缩
        boolean shouldCompress = rawBytes.length >= threshold && !"none".equals(codec);
        byte[] storedBytes = shouldCompress ? gzip(rawBytes) : rawBytes;
        // （3）Base64 编码后组装存储结构，并记录原始与压缩后大小及校验和
        return FlightStoredResult.builder()
                .payload(Base64.getEncoder().encodeToString(storedBytes))
                .codec(shouldCompress ? codec : "none")
                .compressed(shouldCompress)
                .rawSize(rawBytes.length)
                .storedSize(storedBytes.length)
                .checksum(DigestUtil.sha256Hex(rawBytes))
                .contentType("text/plain")
                .finishedAt(System.currentTimeMillis())
                .ownerToken(ownerToken)
                .build();
    }

    /**
     * 将存储结构还原为原始结果内容，必要时先解压并校验摘要。
     *
     * @throws IllegalStateException 摘要校验不一致时抛出
     */
    public String deserialize(FlightStoredResult storedResult) {
        // （1）入参缺失时返回 null
        if (storedResult == null || StrUtil.isBlank(storedResult.getPayload())) {
            return null;
        }
        // （2）Base64 解码，并按压缩标记决定是否解压
        byte[] storedBytes = Base64.getDecoder().decode(storedResult.getPayload());
        byte[] rawBytes = Boolean.TRUE.equals(storedResult.getCompressed())
                ? gunzip(storedBytes)
                : storedBytes;
        // （3）校验 SHA-256 摘要，防止脏数据被误回放
        String checksum = DigestUtil.sha256Hex(rawBytes);
        if (StrUtil.isNotBlank(storedResult.getChecksum()) && !storedResult.getChecksum().equals(checksum)) {
            throw new IllegalStateException("flight result checksum mismatch");
        }
        return new String(rawBytes, StandardCharsets.UTF_8);
    }

    /**
     * 归一化压缩算法名称：为空默认 gzip，仅支持 gzip，其余降级为 none。
     */
    private String normalizeCodec(String codec) {
        // （1）未配置时默认使用 gzip
        if (codec == null || codec.isBlank()) {
            return "gzip";
        }
        // （2）仅识别 gzip，其他取值按不压缩处理
        String normalized = codec.trim().toLowerCase();
        if ("gzip".equals(normalized)) {
            return normalized;
        }
        return "none";
    }

    /**
     * 对字节数组执行 gzip 压缩。
     *
     * @throws IllegalStateException 压缩过程发生 IO 异常时抛出
     */
    private byte[] gzip(byte[] rawBytes) {
        // （1）写入 gzip 输出流并取压缩后的字节
        try {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            try (GZIPOutputStream gzipOutputStream = new GZIPOutputStream(outputStream)) {
                gzipOutputStream.write(rawBytes);
            }
            return outputStream.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("failed to gzip flight result", ex);
        }
    }

    /**
     * 对字节数组执行 gzip 解压。
     *
     * @throws IllegalStateException 解压过程发生 IO 异常时抛出
     */
    private byte[] gunzip(byte[] storedBytes) {
        // （1）通过 GZIPInputStream 读取并全量拷贝到输出流
        try (GZIPInputStream gzipInputStream = new GZIPInputStream(new ByteArrayInputStream(storedBytes))) {
            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            gzipInputStream.transferTo(outputStream);
            return outputStream.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("failed to gunzip flight result", ex);
        }
    }
}
