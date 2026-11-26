package com.banma.interview.storage.infrastructure;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@Data
@ConfigurationProperties(prefix = "banma-interview.minio")
public class MinioProperties {
    /** 服务地址 */
    private String endpoint;

    /** 访问密钥 */
    private String accessKey;

    /** 密钥口令 */
    private String secretKey;

    /** 简历桶名 */
    private String bucketResume;

    /** 预签名默认有效期（秒），默认 7 天；MinIO 上限即 604800 */
    private Integer presignExpirySeconds = 604800;
}
