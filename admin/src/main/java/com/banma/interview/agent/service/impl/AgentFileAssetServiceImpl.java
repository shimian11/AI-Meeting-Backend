package com.banma.interview.agent.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.banma.interview.agent.api.io.resp.AgentFileUploadRespDTO;
import com.banma.interview.agent.application.AgentResolver;
import com.banma.interview.agent.application.BusinessAgentResolver;
import com.banma.interview.agent.application.BusinessAgentScene;
import com.banma.interview.agent.dao.entity.AgentFileAssetDO;
import com.banma.interview.agent.dao.entity.AgentPropertiesDO;
import com.banma.interview.agent.dao.mapper.AgentFileAssetMapper;
import com.banma.interview.agent.service.AgentFileAssetService;
import com.banma.interview.common.convention.exception.ClientException;
import com.banma.interview.common.enums.AgentErrorCodeEnum;
import com.banma.interview.toolkit.xunfei.XingChenAIClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.Date;

/**
 * 智能体文件资产服务实现：负责文件上传到星辰并持久化文件资产记录。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AgentFileAssetServiceImpl extends ServiceImpl<AgentFileAssetMapper, AgentFileAssetDO>
        implements AgentFileAssetService {

    private static final String DEFAULT_BIZ_TYPE = "general";
    private static final String SOURCE_PLATFORM_XINGCHEN = "xingchen";

    private final XingChenAIClient xingChenAIClient;
    private final AgentResolver agentResolver;
    private final BusinessAgentResolver businessAgentResolver;

    /**
     * 上传文件到星辰并保存文件资产记录，返回文件访问地址等信息。
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentFileUploadRespDTO uploadAndPersist(
            String sessionId,
            String bizType,
            String username,
            MultipartFile file) {
        // （1）校验上传文件非空
        if (file == null || file.isEmpty()) {
            throw new ClientException("uploaded file cannot be empty", AgentErrorCodeEnum.AGENT_SAVE_ERROR);
        }

        // （2）解析智能体配置并校验 API 凭证
        AgentPropertiesDO agentProperties = resolveAgentProperties(sessionId);
        if (StrUtil.isBlank(agentProperties.getApiKey()) || StrUtil.isBlank(agentProperties.getApiSecret())) {
            throw new ClientException("agent api credentials are missing", AgentErrorCodeEnum.AGENT_SAVE_ERROR);
        }

        // （3）调用星辰接口上传文件，失败时包装为业务异常
        String fileUrl;
        try {
            fileUrl = xingChenAIClient.uploadFile(file, agentProperties.getApiKey(), agentProperties.getApiSecret());
        } catch (Exception ex) {
            log.error("File upload to xingchen failed, agentId={}, fileName={}",
                    agentProperties.getId(), file.getOriginalFilename(), ex);
            throw new ClientException(
                    "upload file to xingchen failed: " + ex.getMessage(),
                    ex,
                    AgentErrorCodeEnum.AGENT_SAVE_ERROR
            );
        }

        // （4）组装文件资产记录（归一化文件名、扩展名、大小等）并入库
        String originalFileName = normalizeFileName(file.getOriginalFilename());
        Date now = new Date();
        AgentFileAssetDO fileAssetDO = new AgentFileAssetDO();
        fileAssetDO.setAgentId(agentProperties.getId());
        fileAssetDO.setSessionId(StrUtil.isBlank(sessionId) ? null : sessionId.trim());
        fileAssetDO.setUserName(StrUtil.blankToDefault(username, "unknown"));
        fileAssetDO.setBizType(StrUtil.blankToDefault(bizType, DEFAULT_BIZ_TYPE));
        fileAssetDO.setSourcePlatform(SOURCE_PLATFORM_XINGCHEN);
        fileAssetDO.setFileName(originalFileName);
        fileAssetDO.setFileExt(extractFileExt(originalFileName));
        fileAssetDO.setContentType(file.getContentType());
        fileAssetDO.setFileSize(file.getSize());
        fileAssetDO.setFileUrl(fileUrl);
        fileAssetDO.setUploadStatus(1);
        fileAssetDO.setCreateTime(now);
        fileAssetDO.setUpdateTime(now);
        fileAssetDO.setDelFlag(0);

        boolean saved = save(fileAssetDO);
        if (!saved) {
            throw new ClientException("persist uploaded file url failed", AgentErrorCodeEnum.AGENT_SAVE_ERROR);
        }

        // （5）组装上传结果返回
        AgentFileUploadRespDTO respDTO = new AgentFileUploadRespDTO();
        respDTO.setId(fileAssetDO.getId());
        respDTO.setSessionId(fileAssetDO.getSessionId());
        respDTO.setBizType(fileAssetDO.getBizType());
        respDTO.setFileName(fileAssetDO.getFileName());
        respDTO.setFileSize(fileAssetDO.getFileSize());
        respDTO.setContentType(fileAssetDO.getContentType());
        respDTO.setFileUrl(fileAssetDO.getFileUrl());
        respDTO.setCreateTime(fileAssetDO.getCreateTime());
        return respDTO;
    }

    /**
     * 解析文件归属的智能体：优先使用会话绑定的智能体，未绑定时回退到通用业务场景配置。
     */
    private AgentPropertiesDO resolveAgentProperties(String sessionId) {
        // （1）会话非空时优先取绑定的智能体
        if (StrUtil.isNotBlank(sessionId)) {
            AgentPropertiesDO boundAgent = agentResolver.resolveAgent(sessionId, null);
            if (boundAgent != null) {
                return boundAgent;
            }
        }
        // （2）未绑定时回退到通用场景所需的智能体
        return businessAgentResolver.resolveRequired(BusinessAgentScene.GENERAL_AGENT_CHAT);
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
