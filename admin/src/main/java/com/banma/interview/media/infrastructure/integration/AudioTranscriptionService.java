package com.banma.interview.media.infrastructure.integration;

import cn.xfyun.config.SparkIatModelEnum;
import com.banma.interview.common.config.storage.ApplicationStorageProperties;
import com.banma.interview.common.convention.exception.ClientException;
import com.banma.interview.toolkit.xunfei.SparkIatUtil;
import jakarta.annotation.Resource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;

/**
 * Audio transcription service for synchronous and asynchronous speech-to-text flows.
 * 语音转写服务：基于讯飞 IAT 封装同步、异步及回调式的语音转文字流程。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AudioTranscriptionService {

    private final SparkIatUtil sparkIatUtil;
    private final ApplicationStorageProperties storageProperties;
    @Resource(name = "cpuComputeExecutor")
    private ExecutorService cpuComputeExecutor;

    /**
     * 在 CPU 计算线程池中异步执行语音转写，支持回调输出中间结果。
     *
     * @param audioFile              上传的音频文件
     * @param partialResultCallback  中间结果回调，可为 null
     * @return 包含转写文本的异步结果
     */
    public CompletableFuture<String> transcribeAsync(MultipartFile audioFile,
                                                     Consumer<String> partialResultCallback) {
        return CompletableFuture.supplyAsync(() -> {
            // （1）在线程池中执行同步转写流程
            try {
                return transcribeSync(audioFile, partialResultCallback);
            } catch (Exception ex) {
                // （2）转写失败时记录日志并包装为运行时异常抛出
                log.error("Async audio transcription failed", ex);
                throw new RuntimeException("Audio transcription failed: " + ex.getMessage(), ex);
            }
        }, cpuComputeExecutor);
    }

    /**
     * 同步执行语音转写，不接收中间结果回调。
     */
    public String transcribeAudio(MultipartFile audioFile) throws Exception {
        return transcribeSync(audioFile, null);
    }

    /**
     * 同步执行语音转写：校验文件、落临时文件、调用讯飞接口并清理临时文件。
     *
     * @param audioFile             上传的音频文件
     * @param partialResultCallback 中间结果回调，可为 null
     * @return 完整转写文本
     */
    public String transcribeSync(MultipartFile audioFile,
                                 Consumer<String> partialResultCallback) throws Exception {
        // （1）校验音频文件合法性与大小
        validateAudioFile(audioFile);
        // （2）将上传文件写入本地临时文件
        Path tempFile = createTempAudioFile(audioFile);

        try {
            // （3）构建转写配置并调用讯飞同步转写
            SparkIatUtil.IatConfig config = buildTranscriptionConfig();
            String result = sparkIatUtil.transcribeSync(tempFile.toFile(), config, partialResultCallback);
            log.info("Audio transcription completed, file={}, resultLength={}",
                    audioFile.getOriginalFilename(), result.length());
            return result;
        } finally {
            // （4）无论成功与否都清理临时文件
            cleanupTempFile(tempFile);
        }
    }

    /**
     * 以回调方式发起异步转写，成功后清理临时文件并回调结果。
     *
     * @param audioFile 上传的音频文件
     * @param callback  转写结果回调
     */
    public void transcribeWithCallback(MultipartFile audioFile,
                                       AudioTranscriptionCallback callback) {
        try {
            // （1）校验音频并落临时文件
            validateAudioFile(audioFile);
            Path tempFile = createTempAudioFile(audioFile);
            SparkIatUtil.IatConfig config = buildTranscriptionConfig();

            // （2）注册讯飞异步回调，成功/失败时清理临时文件并转发结果
            sparkIatUtil.transcribeAsync(tempFile.toFile(), config, new SparkIatUtil.IatCallback() {
                /**
                 * 转写成功时清理临时文件并回调最终结果。
                 */
                @Override
                public void onSuccess(String result) {
                    cleanupTempFile(tempFile);
                    callback.onSuccess(result);
                }

                /**
                 * 转写失败时清理临时文件并回调异常。
                 */
                @Override
                public void onError(Exception error) {
                    cleanupTempFile(tempFile);
                    callback.onError(error);
                }

                /**
                 * 中间结果直接透传给业务回调。
                 */
                @Override
                public void onPartialResult(String partialResult) {
                    callback.onPartialResult(partialResult);
                }
            });
        } catch (Exception ex) {
            // （3）启动失败时记录日志并回调异常
            log.error("Failed to start async audio transcription", ex);
            callback.onError(ex);
        }
    }

    /**
     * 校验音频文件：非空、大小不超过 50MB 且格式受支持。
     */
    private void validateAudioFile(MultipartFile audioFile) {
        // （1）校验文件存在且非空
        if (audioFile == null || audioFile.isEmpty()) {
            throw new ClientException("audio file must not be empty");
        }

        // （2）校验文件大小上限 50MB
        long maxSize = 50L * 1024 * 1024;
        if (audioFile.getSize() > maxSize) {
            throw new ClientException("audio file size must not exceed 50MB");
        }

        // （3）校验扩展名格式是否受支持，m4a/aac 额外提示转换
        String filename = audioFile.getOriginalFilename();
        if (filename == null) {
            return;
        }

        String extension = getFileExtension(filename).toLowerCase();
        if (isSupportedAudioFormat(extension)) {
            return;
        }

        if (".m4a".equals(extension) || ".aac".equals(extension)) {
            throw new ClientException("unsupported audio format: " + extension
                    + ". Supported formats are pcm, wav, mp3 and flac. Please convert the file and retry.");
        }
        throw new ClientException("unsupported audio format: " + extension
                + ". Supported formats are pcm, wav, mp3 and flac.");
    }

    /**
     * 判断扩展名是否为受支持的音频格式（pcm/wav/mp3/flac）。
     */
    private boolean isSupportedAudioFormat(String extension) {
        return ".pcm".equals(extension)
                || ".wav".equals(extension)
                || ".mp3".equals(extension)
                || ".flac".equals(extension);
    }

    /**
     * 提取文件名中的扩展名（含点号），无扩展名时返回空串。
     */
    private String getFileExtension(String filename) {
        int lastDotIndex = filename.lastIndexOf('.');
        return lastDotIndex > 0 ? filename.substring(lastDotIndex) : "";
    }

    /**
     * 将上传的音频复制到配置的临时目录，返回临时文件路径。
     */
    private Path createTempAudioFile(MultipartFile audioFile) throws IOException {
        // （1）确保临时目录存在
        Path tempDir = storageProperties.getAudioTempPath();
        Files.createDirectories(tempDir);

        // （2）生成唯一临时文件名并复制文件内容
        String extension = getFileExtension(audioFile.getOriginalFilename());
        String fileName = "audio_transcription_" + System.currentTimeMillis()
                + "_" + Thread.currentThread().getId() + extension;
        Path tempFile = tempDir.resolve(fileName);

        try (var inputStream = audioFile.getInputStream()) {
            Files.copy(inputStream, tempFile, StandardCopyOption.REPLACE_EXISTING);
        }

        log.debug("Created audio temp file={}, size={} bytes", tempFile, audioFile.getSize());
        return tempFile;
    }

    /**
     * 构建默认的讯飞转写配置。
     */
    private SparkIatUtil.IatConfig buildTranscriptionConfig() {
        return sparkIatUtil.createDefaultConfig();
    }

    /**
     * 构建自定义的讯飞转写配置，可指定模型、动态修正参数与超时时间。
     */
    @SuppressWarnings("unused")
    private SparkIatUtil.IatConfig buildCustomTranscriptionConfig(SparkIatModelEnum model,
                                                                  String dwa,
                                                                  int timeoutSeconds) {
        return sparkIatUtil.createCustomConfig(model, dwa, timeoutSeconds);
    }

    /**
     * 删除转写临时文件，删除失败时改为 JVM 退出时删除。
     */
    private void cleanupTempFile(Path tempFile) {
        // （1）文件为空或不存在时直接返回
        if (tempFile == null || !Files.exists(tempFile)) {
            return;
        }

        try {
            // （2）尝试立即删除临时文件
            Files.delete(tempFile);
            log.debug("Deleted temp file={}", tempFile);
        } catch (Exception ex) {
            // （3）删除失败时标记为退出时删除并记录告警
            log.warn("Failed to delete temp file={}, reason={}", tempFile, ex.getMessage());
            tempFile.toFile().deleteOnExit();
        }
    }

    /**
     * 语音转写回调接口，供业务侧接收最终结果、异常和中间结果。
     */
    public interface AudioTranscriptionCallback {

        /**
         * 转写成功时回调最终文本。
         */
        void onSuccess(String result);

        /**
         * 转写失败时回调异常。
         */
        void onError(Exception error);

        /**
         * 收到中间转写结果时回调，默认空实现。
         */
        default void onPartialResult(String partialResult) {
        }
    }
}
