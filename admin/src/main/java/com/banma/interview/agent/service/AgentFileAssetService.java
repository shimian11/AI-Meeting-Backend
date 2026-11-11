package com.banma.interview.agent.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.banma.interview.agent.api.io.resp.AgentFileUploadRespDTO;
import com.banma.interview.agent.dao.entity.AgentFileAssetDO;
import org.springframework.web.multipart.MultipartFile;

public interface AgentFileAssetService extends IService<AgentFileAssetDO> {

    AgentFileUploadRespDTO uploadAndPersist(String sessionId, String bizType, String username, MultipartFile file);
}
