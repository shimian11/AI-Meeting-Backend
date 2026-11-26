package com.banma.interview.storage.infrastructure;

import io.minio.MinioClient;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * MinIO 客户端装配。
 *
 * <p>在这里做非空校验：`MinioClient.builder().build()` 不校验连接，
 * 缺配置时会拖到第一次上传才炸；提前抛错能让启动即失败，问题定位成本低得多。</p>
 */
@Configuration
@RequiredArgsConstructor
public class MinioConfig {

    private final MinioProperties minioProperties;

    /**
     * 构建minio客户端，使用Bean单例
     * MinioClient 内部封装了底层的 HTTP 客户端（OkHttpClient），
     * 并且维护了一个线程安全的 HTTP 连接池。
     * 做成单例，所有的文件上传、下载请求都可以复用这些已经建立的 TCP 连接，极大减少了频繁建立和断开连接的开销。
     */
    @Bean
    public MinioClient minioClient() {
        //(1)启动前检验必填项
        if (!StringUtils.hasText(minioProperties.getEndpoint())) {
            throw new IllegalStateException("MinIO 未配置：缺少 MINIO_ENDPOINT");
        }
        if (!StringUtils.hasText(minioProperties.getAccessKey())
                || !StringUtils.hasText(minioProperties.getSecretKey())) {
            throw new IllegalStateException("MinIO 未配置：缺少 MINIO_ACCESS_KEY / MINIO_SECRET_KEY");
        }
        //(2)构建客户端
        return MinioClient.builder()
                .endpoint(minioProperties.getEndpoint())
                .credentials(minioProperties.getAccessKey(), minioProperties.getSecretKey())
                .build();
    }
}
