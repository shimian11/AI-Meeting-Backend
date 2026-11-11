package com.banma.interview.ai.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.banma.interview.ai.service.AiPropertiesService;
import com.banma.interview.common.convention.exception.ClientException;
import com.banma.interview.ai.dao.entity.AiPropertiesDO;
import com.banma.interview.ai.dao.mapper.AiPropertiesMapper;
import com.banma.interview.ai.api.io.req.AiPropertiesCreateReqDTO;
import com.banma.interview.ai.api.io.req.AiPropertiesPageReqDTO;
import com.banma.interview.ai.api.io.req.AiPropertiesUpdateReqDTO;
import com.banma.interview.ai.api.io.resp.AiModelOptionRespDTO;
import com.banma.interview.ai.api.io.resp.AiPropertiesRespDTO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * AI 配置服务实现：负责 AI 配置的增删改查、启用状态管理与 apiKey 脱敏。
 */
@Service
@RequiredArgsConstructor
public class AiPropertiesServiceImpl extends ServiceImpl<AiPropertiesMapper, AiPropertiesDO> implements AiPropertiesService {

    // ... (其他方法省略)

    /**
     * 查询所有启用的 AI 配置，并转换为模型下拉选项。
     */
    @Override
    public List<AiModelOptionRespDTO> getAvailableAiModels() {
        // （1）查询所有启用的 AI 配置
        List<AiPropertiesRespDTO> enabledProperties = getAllEnabledAiProperties();
        // （2）转换为模型下拉选项
        return enabledProperties.stream()
                .map(prop -> AiModelOptionRespDTO.builder()
                        .id(prop.getId())
                        .aiName(prop.getAiName())
                        .aiType(Integer.valueOf(prop.getAiType()))
                        .build())
                .collect(Collectors.toList());
    }

    /**
     * 新增 AI 配置：校验名称唯一，补全时间与逻辑删除标记后入库。
     */
    @Override
    public void createAiProperties(AiPropertiesCreateReqDTO requestParam) {
        // （1）按名称查重（仅统计未删除记录）
        LambdaQueryWrapper<AiPropertiesDO> queryWrapper = Wrappers.lambdaQuery(AiPropertiesDO.class)
                .eq(AiPropertiesDO::getAiName, requestParam.getAiName())
                .eq(AiPropertiesDO::getDelFlag, 0);

        if (baseMapper.selectCount(queryWrapper) > 0) {
            throw new ClientException("AI名称已存在");
        }

        // （2）复制请求属性并补全创建/更新时间与逻辑删除标记
        AiPropertiesDO aiPropertiesDO = new AiPropertiesDO();
        BeanUtil.copyProperties(requestParam, aiPropertiesDO);
        aiPropertiesDO.setCreateTime(new Date());
        aiPropertiesDO.setUpdateTime(new Date());
        aiPropertiesDO.setDelFlag(0);

        // （3）未指定启用状态时默认启用
        if (aiPropertiesDO.getIsEnabled() == null) {
            aiPropertiesDO.setIsEnabled(1);
        }

        baseMapper.insert(aiPropertiesDO);
    }

    /**
     * 更新 AI 配置：校验记录存在，名称变更时校验唯一性后更新。
     */
    @Override
    public void updateAiProperties(AiPropertiesUpdateReqDTO requestParam) {
        // （1）查询记录并校验未删除
        AiPropertiesDO existingRecord = baseMapper.selectById(requestParam.getId());
        if (existingRecord == null || existingRecord.getDelFlag() == 1) {
            throw new ClientException("AI配置不存在");
        }

        // （2）名称变更时校验重名（排除自身）
        if (StrUtil.isNotBlank(requestParam.getAiName()) && !requestParam.getAiName().equals(existingRecord.getAiName())) {
            LambdaQueryWrapper<AiPropertiesDO> queryWrapper = Wrappers.lambdaQuery(AiPropertiesDO.class)
                    .eq(AiPropertiesDO::getAiName, requestParam.getAiName())
                    .eq(AiPropertiesDO::getDelFlag, 0)
                    .ne(AiPropertiesDO::getId, requestParam.getId());

            if (baseMapper.selectCount(queryWrapper) > 0) {
                throw new ClientException("AI名称已存在");
            }
        }

        // （3）复制属性并刷新更新时间后更新
        AiPropertiesDO aiPropertiesDO = new AiPropertiesDO();
        BeanUtil.copyProperties(requestParam, aiPropertiesDO);
        aiPropertiesDO.setUpdateTime(new Date());

        baseMapper.updateById(aiPropertiesDO);
    }

    /**
     * 逻辑删除 AI 配置（置删除标记，不物理删除）。
     */
    @Override
    public void deleteAiProperties(Long id) {
        // （1）查询记录并校验未删除
        AiPropertiesDO existingRecord = baseMapper.selectById(id);
        if (existingRecord == null || existingRecord.getDelFlag() == 1) {
            throw new ClientException("AI配置不存在");
        }

        // （2）构造更新条件：设置删除标记并刷新更新时间
        LambdaUpdateWrapper<AiPropertiesDO> updateWrapper = Wrappers.lambdaUpdate(AiPropertiesDO.class)
                .eq(AiPropertiesDO::getId, id)
                .set(AiPropertiesDO::getDelFlag, 1)
                .set(AiPropertiesDO::getUpdateTime, new Date());

        baseMapper.update(null, updateWrapper);
    }

    /**
     * 按 id 查询 AI 配置详情，并对 apiKey 做脱敏。
     */
    @Override
    public AiPropertiesRespDTO getAiPropertiesById(Long id) {
        // （1）查询记录并校验未删除
        AiPropertiesDO aiPropertiesDO = baseMapper.selectById(id);
        if (aiPropertiesDO == null || aiPropertiesDO.getDelFlag() == 1) {
            throw new ClientException("AI配置不存在");
        }

        // （2）复制属性为响应对象
        AiPropertiesRespDTO respDTO = new AiPropertiesRespDTO();
        BeanUtil.copyProperties(aiPropertiesDO, respDTO);

        // （3）apiKey 非空时脱敏后返回
        if (StrUtil.isNotBlank(respDTO.getApiKey())) {
            respDTO.setApiKey(maskApiKey(respDTO.getApiKey()));
        }

        return respDTO;
    }

    /**
     * 按名称、类型与启用状态分页查询 AI 配置，并脱敏 apiKey。
     */
    @Override
    public IPage<AiPropertiesRespDTO> pageAiProperties(AiPropertiesPageReqDTO requestParam) {
        // （1）构建查询条件（名称/类型/启用状态，按创建时间倒序）
        LambdaQueryWrapper<AiPropertiesDO> queryWrapper = Wrappers.lambdaQuery(AiPropertiesDO.class)
                .eq(AiPropertiesDO::getDelFlag, 0)
                .like(StrUtil.isNotBlank(requestParam.getAiName()), AiPropertiesDO::getAiName, requestParam.getAiName())
                .eq(StrUtil.isNotBlank(requestParam.getAiType()), AiPropertiesDO::getAiType, requestParam.getAiType())
                .eq(requestParam.getIsEnabled() != null, AiPropertiesDO::getIsEnabled, requestParam.getIsEnabled())
                .orderByDesc(AiPropertiesDO::getCreateTime);

        // （2）执行分页查询
        IPage<AiPropertiesDO> page = baseMapper.selectPage(requestParam, queryWrapper);

        // （3）转换为响应 DTO，并在 apiKey 非空时脱敏
        IPage<AiPropertiesRespDTO> resultPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        List<AiPropertiesRespDTO> records = page.getRecords().stream()
                .map(record -> {
                    AiPropertiesRespDTO respDTO = new AiPropertiesRespDTO();
                    BeanUtil.copyProperties(record, respDTO);
                    if (StrUtil.isNotBlank(respDTO.getApiKey())) {
                        respDTO.setApiKey(maskApiKey(respDTO.getApiKey()));
                    }
                    return respDTO;
                })
                .collect(Collectors.toList());

        resultPage.setRecords(records);
        return resultPage;
    }

    /**
     * 查询所有已启用的 AI 配置，并脱敏 apiKey。
     */
    @Override
    public List<AiPropertiesRespDTO> listEnabledAiProperties() {
        // （1）查询未删除且启用的配置，按创建时间倒序
        LambdaQueryWrapper<AiPropertiesDO> queryWrapper = Wrappers.lambdaQuery(AiPropertiesDO.class)
                .eq(AiPropertiesDO::getDelFlag, 0)
                .eq(AiPropertiesDO::getIsEnabled, 1)
                .orderByDesc(AiPropertiesDO::getCreateTime);

        List<AiPropertiesDO> list = baseMapper.selectList(queryWrapper);

        // （2）转换为响应 DTO，并在 apiKey 非空时脱敏
        return list.stream()
                .map(record -> {
                    AiPropertiesRespDTO respDTO = new AiPropertiesRespDTO();
                    BeanUtil.copyProperties(record, respDTO);
                    if (StrUtil.isNotBlank(respDTO.getApiKey())) {
                        respDTO.setApiKey(maskApiKey(respDTO.getApiKey()));
                    }
                    return respDTO;
                })
                .collect(Collectors.toList());
    }

    /**
     * 启用或停用指定 AI 配置。
     */
    @Override
    public void toggleAiPropertiesStatus(Long id, Integer isEnabled) {
        // （1）查询记录并校验未删除
        AiPropertiesDO existingRecord = baseMapper.selectById(id);
        if (existingRecord == null || existingRecord.getDelFlag() == 1) {
            throw new ClientException("AI配置不存在");
        }

        // （2）按 id 更新启用状态与更新时间
        LambdaUpdateWrapper<AiPropertiesDO> updateWrapper = Wrappers.lambdaUpdate(AiPropertiesDO.class)
                .eq(AiPropertiesDO::getId, id)
                .set(AiPropertiesDO::getIsEnabled, isEnabled)
                .set(AiPropertiesDO::getUpdateTime, new Date());

        baseMapper.update(null, updateWrapper);
    }

    /**
     * 获取所有启用的 AI 配置（等价于 listEnabledAiProperties）。
     */
    @Override
    public List<AiPropertiesRespDTO> getAllEnabledAiProperties() {
        return listEnabledAiProperties();
    }

    /**
     * 按 AI 类型查询最新一条启用的配置，未匹配时返回 null。
     */
    @Override
    public AiPropertiesDO getEnabledByAiType(String aiType) {
        LambdaQueryWrapper<AiPropertiesDO> queryWrapper = Wrappers.lambdaQuery(AiPropertiesDO.class)
                .eq(AiPropertiesDO::getDelFlag, 0)
                .eq(AiPropertiesDO::getIsEnabled, 1)
                .eq(AiPropertiesDO::getAiType, aiType)
                .orderByDesc(AiPropertiesDO::getCreateTime)
                .last("LIMIT 1");

        return baseMapper.selectOne(queryWrapper);
    }

    /**
     * 获取默认豆包 AI 配置，不存在或未启用时抛出业务异常。
     */
    @Override
    public AiPropertiesDO getDefaultDoubaoConfig() {
        // （1）按类型 doubao 查询启用配置
        AiPropertiesDO doubaoConfig = getEnabledByAiType("doubao");
        // （2）未查到配置时抛业务异常
        if (doubaoConfig == null) {
            throw new ClientException("豆包AI配置不存在或未启用");
        }
        return doubaoConfig;
    }

    /**
     * 对 apiKey 脱敏：仅保留首尾各 4 位，长度不足时统一返回 ****。
     */
    private String maskApiKey(String apiKey) {
        if (StrUtil.isBlank(apiKey) || apiKey.length() <= 8) {
            return "****";
        }
        return apiKey.substring(0, 4) + "****" + apiKey.substring(apiKey.length() - 4);
    }
}

