package com.banma.interview.storage.infrastructure;

import com.banma.interview.common.convention.exception.ClientException;
import com.banma.interview.common.enums.FileErrorCodeEnum;
import io.minio.*;
import io.minio.errors.*;
import io.minio.http.Method;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.TimeUnit;

/**
 * MinIO 实现。
 *
 * <p>两个约定：① 上传返回 objectKey 而不是 URL——URL 有有效期，必须用时现签；
 * ② 异常统一「日志留栈 + 抛业务异常」，不把存储细节暴露给前端。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MinioStorageServiceImpl implements MinioStorageService{

    private final MinioClient  minioClient;
    private final MinioProperties  minioProperties;

    @Override
    public void ensureBucket(String bucket) {
        try {
            //(1)已存在则直接返回
            boolean isExist = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
            if(isExist) return;
            //(2)不存在则创建
            minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
            log.info("Minio桶已创建: {}",bucket);
        } catch (Exception e) {
            log.error("MinIO 桶初始化失败, bucket={}", bucket, e);
            throw new ClientException("文件存储初始化失败，请检查 MinIO 是否启动", FileErrorCodeEnum.FILE_UPLOAD_FAILED);
        }
    }

    @Override
    public String upload(String bucket, String objectKey, InputStream in, long size, String contentType) {
        try{
            //(1)写如对象：size未知时传入-1，让minio客户端自动分片
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(bucket)
                    .stream(in, size, -1)
                    .contentType(contentType)
                    .build());
            log.info("MinIO 上传成功, bucket={}, key={}, size={}", bucket, objectKey, size);
            return objectKey;
        }catch (Exception e){
            log.error("MinIO 上传失败, bucket={}, key={}", bucket, objectKey, e);
            throw new ClientException("文件上传失败，请稍后重试", FileErrorCodeEnum.FILE_UPLOAD_FAILED);
        }
    }

    @Override
    public String presignedGetUrl(String bucket, String objectKey, Integer expirySeconds) {
        //（1）未指定过期时间则使用默认
        Integer expiry = (expirySeconds == null || expirySeconds <= 0)
                ? minioProperties.getPresignExpirySeconds() : expirySeconds;
        try{
            return minioClient.getPresignedObjectUrl(GetPresignedObjectUrlArgs.builder()
                    .method(Method.GET)
                    .bucket(bucket)
                    .object(objectKey)
                    .expiry(expiry, TimeUnit.SECONDS)
                    .build());
        }catch (Exception e){
            log.error("MinIO 预签名失败, bucket={}, key={}", bucket, objectKey, e);
            throw new ClientException("生成文件访问地址失败", FileErrorCodeEnum.FILE_PRESIGN_FAILED);
        }
    }

    @Override
    public boolean exists(String bucket, String objectKey) {
        try {
            minioClient.statObject(StatObjectArgs.builder().bucket(bucket).object(objectKey).build());
            return true;
        } catch (Exception e) {
            // stat 失败通常就是不存在，按 false 返回，不抛异常
            return false;
        }
    }

    @Override
    public void delete(String bucket, String objectKey) {
        try {
            minioClient.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(objectKey).build());
            log.info("MinIO 删除成功, bucket={}, key={}", bucket, objectKey);
        } catch (Exception e) {
            log.error("MinIO 删除失败, bucket={}, key={}", bucket, objectKey, e);
            throw new ClientException("文件删除失败", FileErrorCodeEnum.FILE_DELETE_FAILED);
        }
    }
}
