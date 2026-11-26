package com.banma.interview.storage.infrastructure;

import java.io.InputStream;

/**
 * 对象存储能力（当前只实现 MinIO）。
 */
public interface MinioStorageService {

    /**
     * 确保桶存在，不存在则创建。
     *
     * @param bucket 桶名
     */
    void ensureBucket(String bucket);

    /**
     * 上传对象。
     *
     * @param bucket      桶名
     * @param objectKey   对象键（如 resume/{userId}/{uuid}.pdf）
     * @param in          输入流
     * @param size        字节数；未知传 -1
     * @param contentType MIME 类型
     * @return 实际写入的对象键
     */
    String upload(String bucket, String objectKey, InputStream in, long size, String contentType);

    /**
     * 生成下载用的预签名 URL。
     *
     * @param bucket        桶名
     * @param objectKey     对象键
     * @param expirySeconds 有效期（秒）；传 null 用配置默认值
     * @return 可直接在浏览器打开的 URL
     */
    String presignedGetUrl(String bucket, String objectKey, Integer expirySeconds);

    /**
     * 判断对象是否存在。
     *
     * @param bucket    桶名
     * @param objectKey 对象键
     * @return true 表示存在
     */
    boolean exists(String bucket, String objectKey);

    /**
     * 删除对象（不存在时不报错）。
     *
     * @param bucket    桶名
     * @param objectKey 对象键
     */
    void delete(String bucket, String objectKey);
}
