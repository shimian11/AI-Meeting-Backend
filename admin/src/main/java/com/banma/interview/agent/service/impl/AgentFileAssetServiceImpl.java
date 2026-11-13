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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.util.Date;

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
    /** 存储平台标识：讯飞下线后统一为 minio（6G 接入后生效） */
    private static final String SOURCE_PLATFORM_MINIO = "minio";

    private final AgentResolver agentResolver;
    private final BusinessAgentResolver businessAgentResolver;

    /**
     * 保存文件资产记录并返回文件访问地址等信息。
     *
     * 步骤：
     *   1. 校验上传文件非空
     *   2. 解析智能体配置
     *   3. 存储文件（原讯飞 uploadFile 已删除；MinIO 接入在 6G，此处暂抛「未接入」）
     *   4. 组装文件资产记录并入库
     *   5. 组装上传结果返回
     */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public AgentFileUploadRespDTO uploadAndPersist(
            String sessionId,
            String bizType,
            String username,
            MultipartFile file) {
        // 步骤 1：校验上传文件非空
        if (file == null || file.isEmpty()) {
            throw new ClientException("uploaded file cannot be empty", AgentErrorCodeEnum.AGENT_SAVE_ERROR);
        }

        // 步骤 2：解析智能体配置
        AgentPropertiesDO agentProperties = resolveAgentProperties(sessionId);

        // 步骤 3：文件存储。讯飞 uploadFile 已删除；MinIO 在 6G 接入（ADR-016），
        //         接入前上传接口不可用——直接报业务错误而非静默假成功
        String fileUrl;
        try {
            throw new ClientException(
                    "文件存储后端切换中：讯飞上传已下线，MinIO 将于题库后端（6G）接入",
                    AgentErrorCodeEnum.AGENT_SAVE_ERROR);
        } catch (ClientException ex) {
            throw ex;
        }

        // 步骤 4~5（MinIO 接入后恢复）：组装资产记录入库并返回
        // String originalFileName = normalizeFileName(file.getOriginalFilename());
        // ... 原 (4)(5) 逻辑保留在 git 历史（commit e3b3c99 之前的版本），6G 时参照恢复
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
