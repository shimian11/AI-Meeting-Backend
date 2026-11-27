package com.banma.interview.agent.service.impl;

import cn.hutool.core.util.StrUtil;
import com.banma.interview.storage.infrastructure.MinioProperties;
import com.banma.interview.storage.infrastructure.MinioStorageService;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.banma.interview.agent.api.io.resp.AgentFileUploadRespDTO;
import com.banma.interview.agent.dao.entity.AgentFileAssetDO;
import com.banma.interview.agent.dao.mapper.AgentFileAssetMapper;
import com.banma.interview.agent.service.AgentFileAssetService;
import com.banma.interview.common.convention.exception.ClientException;
import com.banma.interview.common.enums.FileErrorCodeEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.UUID;

/**
 * 智能体文件资产服务实现：负责文件存储并持久化文件资产记录。
 * 上传链路改为 MinIO 存储（6G，ADR-016）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentFileAssetServiceImpl extends ServiceImpl<AgentFileAssetMapper, AgentFileAssetDO>
        implements AgentFileAssetService {

    private static final String DEFAULT_BIZ_TYPE = "general";
    private static final String SOURCE_PLATFORM_MINIO = "minio";
    /** 简历大小上限：20MB */
    private static final long MAX_RESUME_SIZE = 20L * 1024 * 1024;
    /** PDF 文件头魔数 */
    private static final String PDF_MAGIC = "%PDF-";
    private static final String RESUME_EXT = "pdf";

    private final MinioStorageService  minioStorageService;
    private final MinioProperties  minioProperties;

    /**
     * 保存文件资产记录并返回文件访问地址等信息。
     *
     * 步骤：
     * 1、检验上传文件非空
     * 2、校验文件内容（大小，以及文件格式是否正确）
     * 3、上传到Minio（objectKey=resume/{yyyyMM}/{uuid}.pdf)
     * 4、落库agent_file_asset
     * 5、返回预签名url
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentFileUploadRespDTO uploadAndPersist(
            String sessionId,
            String bizType,
            String username,
            MultipartFile file) {
        // 1：校验上传文件非空
        if (file == null || file.isEmpty()) {
            throw new ClientException("上传文件为空", FileErrorCodeEnum.FILE_EMPTY);
        }

        //2：校验内容(扩展名+文件头校验)
        validateResumeFile(file);

        //3、上传到minIO
        String originalFileName = normalizeFileName(file.getOriginalFilename());
        String objectKey = buildResumeObjectKey(extractFileExt(originalFileName));
        String bucket = minioProperties.getBucketResume();
        try(InputStream in = file.getInputStream()){
            minioStorageService.ensureBucket(bucket);
            minioStorageService.upload(bucket,objectKey,in,file.getSize(),MediaType.APPLICATION_PDF_VALUE);
        }catch (IOException ex){
            log.error("读取上传文件失败, fileName={}", originalFileName, ex);
            throw new ClientException("读取上传文件失败", FileErrorCodeEnum.FILE_READ_FAILED);
        }

        //4、落库
        Date now = new Date();
        AgentFileAssetDO fileAssetDO = new AgentFileAssetDO();
        fileAssetDO.setSessionId(StrUtil.isBlank(sessionId) ? null : sessionId.trim());
        fileAssetDO.setUserName(StrUtil.blankToDefault(username, "unknown"));
        fileAssetDO.setBizType(StrUtil.blankToDefault(bizType, DEFAULT_BIZ_TYPE));
        fileAssetDO.setSourcePlatform(SOURCE_PLATFORM_MINIO);
        fileAssetDO.setFileName(originalFileName);
        fileAssetDO.setFileExt(extractFileExt(originalFileName));
        fileAssetDO.setContentType(MediaType.APPLICATION_PDF_VALUE);
        fileAssetDO.setFileSize(file.getSize());
        // 库里存逻辑地址：预签名 URL 有有效期，存进去明天就是死链
        fileAssetDO.setFileUrl(String.format("minio://%s/%s", bucket, objectKey));
        fileAssetDO.setMinioKey(objectKey);
        fileAssetDO.setUploadStatus(1);
        fileAssetDO.setCreateTime(now);
        fileAssetDO.setUpdateTime(now);
        fileAssetDO.setDelFlag(0);

        if (!save(fileAssetDO)) {
            throw new ClientException("保存文件资产记录失败", FileErrorCodeEnum.FILE_RECORD_SAVE_FAILED);
        }

        // 步骤 5：组装返回（fileUrl 现签，前端可直接打开；长期引用请用 id 重新签名）
        AgentFileUploadRespDTO respDTO = new AgentFileUploadRespDTO();
        respDTO.setId(fileAssetDO.getId());
        respDTO.setSessionId(fileAssetDO.getSessionId());
        respDTO.setBizType(fileAssetDO.getBizType());
        respDTO.setFileName(fileAssetDO.getFileName());
        respDTO.setFileSize(fileAssetDO.getFileSize());
        respDTO.setContentType(fileAssetDO.getContentType());
        respDTO.setFileUrl(minioStorageService.presignedGetUrl(bucket, objectKey, null));
        respDTO.setCreateTime(fileAssetDO.getCreateTime());
        return respDTO;
    }

    /**
     * 校验简历：大小、扩展名、文件头三重校验。
     * @param file 上传的简历文件
     */
    private void validateResumeFile(MultipartFile file) {
        //1、大小
        if(file.getSize() > MAX_RESUME_SIZE) {
            throw new ClientException("简历不能超过 20MB", FileErrorCodeEnum.FILE_TOO_LARGE);
        }
        //2、扩展名
        String ext = extractFileExt(normalizeFileName(file.getOriginalFilename()));
        if(!RESUME_EXT.equals(ext)){
            throw new ClientException("暂时只支持 PDF 简历", FileErrorCodeEnum.FILE_TYPE_UNSUPPORTED);
        }
        //3、文件头
        try(InputStream in = file.getInputStream()){
            byte[] head = new byte[PDF_MAGIC.length()];
            int read = in.read(head);
            if(read < head.length || !PDF_MAGIC.equals(new String(head, StandardCharsets.US_ASCII))){
                throw new ClientException("文件内容不是有效的 PDF", FileErrorCodeEnum.FILE_CONTENT_INVALID);
            }
        }catch (IOException ex) {
            log.error("读取文件头失败, fileName={}", file.getOriginalFilename(), ex);
            throw new ClientException("读取上传文件失败", FileErrorCodeEnum.FILE_READ_FAILED);
        }
    }

    /**
     * 生成简历对象键`resume/{yyyyMM}/{uuid}.{ext}`
     */
    private String buildResumeObjectKey(String ext){
        String month = new SimpleDateFormat("yyyyMM").format(new Date());
        String suffix = StrUtil.isBlank(ext) ? RESUME_EXT : ext;
        return String.format("resume/%s/%s.%s", month, UUID.randomUUID(), suffix);
    }

    /**
     * 规整文件名：空值生成带时间戳的默认名称，否则去除首尾空白。
     */
    private String normalizeFileName(String originalFileName) {
        if (StrUtil.isBlank(originalFileName)) {
            return "unknown_" + System.currentTimeMillis();
        }
        return originalFileName.trim();
    }

    /**
     * 提取文件扩展名并转小写，无扩展名时返回 null。
     */
    private String extractFileExt(String fileName) {
        if (StrUtil.isBlank(fileName)) {
            return null;
        }
        int idx = fileName.lastIndexOf('.');
        if (idx < 0 || idx == fileName.length() - 1) {
            return null;
        }
        return fileName.substring(idx + 1).toLowerCase();
    }
}
