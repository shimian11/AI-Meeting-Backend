package com.banma.interview.agent.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.banma.interview.agent.service.AgentPropertiesService;
import com.banma.interview.agent.service.AgentTagService;
import com.banma.interview.common.convention.result.PageInfo;
import com.banma.interview.agent.dao.entity.AgentPropertiesDO;
import com.banma.interview.agent.dao.entity.AgentTag;
import com.banma.interview.agent.dao.mapper.AgentPropertiesMapper;
import com.banma.interview.agent.api.io.req.AgentPropertiesReqDTO;
import com.banma.interview.agent.api.io.resp.AgentPropertiesRespDTO;
import com.banma.interview.common.enums.AgentTagType;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.BeanUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 智能体配置服务实现：负责配置的增删改查及关联标签的维护。
 */
@Service
@RequiredArgsConstructor
public class AgentPropertiesServiceImpl extends ServiceImpl<AgentPropertiesMapper, AgentPropertiesDO>
    implements AgentPropertiesService {

    private final AgentTagService agentTagService;

    /**
     * 新增智能体配置，并保存其关联标签。
     */
    @Override
    @Transactional
    public void create(AgentPropertiesReqDTO requestParam) {
        // （1）复制请求属性并补全时间与逻辑删除标记后入库
        AgentPropertiesDO agentPropertiesDO = new AgentPropertiesDO();
        BeanUtils.copyProperties(requestParam, agentPropertiesDO);
        agentPropertiesDO.setCreateTime(new Date());
        agentPropertiesDO.setUpdateTime(new Date());
        agentPropertiesDO.setDelFlag(0);
        save(agentPropertiesDO);
        
        // 处理标签数据
        if (requestParam.getTagCodes() != null && !requestParam.getTagCodes().isEmpty()) {
            createAgentTags(agentPropertiesDO.getId(), requestParam.getTagCodes());
        }
    }

    /**
     * 逻辑删除智能体配置及其关联标签。
     */
    @Override
    @Transactional
    public void delete(Long id) {
        // （1）逻辑删除智能体配置
        LambdaUpdateWrapper<AgentPropertiesDO> updateWrapper = Wrappers.lambdaUpdate(AgentPropertiesDO.class)
                .eq(AgentPropertiesDO::getId, id)
                .set(AgentPropertiesDO::getDelFlag, 1)
                .set(AgentPropertiesDO::getUpdateTime, new Date());
        baseMapper.update(null, updateWrapper);
        
        // 删除相关标签数据
        LambdaUpdateWrapper<AgentTag> tagUpdateWrapper = Wrappers.lambdaUpdate(AgentTag.class)
                .eq(AgentTag::getAgentId, id)
                .set(AgentTag::getDelFlag, 1);
        agentTagService.update(tagUpdateWrapper);
    }

    /**
     * 更新智能体配置字段，并按需重建其标签。
     */
    @Override
    @Transactional
    public void update(AgentPropertiesReqDTO requestParam) {
        // （1）按 id 更新配置字段并刷新更新时间
        LambdaUpdateWrapper<AgentPropertiesDO> updateWrapper = Wrappers.lambdaUpdate(AgentPropertiesDO.class)
                .eq(AgentPropertiesDO::getId, requestParam.getId())
                .set(AgentPropertiesDO::getAgentName, requestParam.getAgentName())
                .set(AgentPropertiesDO::getUpdateTime, new Date());
        update(updateWrapper);
        
        // 更新标签数据
        if (requestParam.getTagCodes() != null) {
            updateAgentTags(requestParam.getId(), requestParam.getTagCodes());
        }
    }

    /**
     * 按智能体名称查询配置详情，并填充其标签。
     */
    @Override
    public AgentPropertiesRespDTO getByName(String name) {
        // （1）按名称查询未删除记录
        LambdaQueryWrapper<AgentPropertiesDO> queryWrapper = Wrappers.lambdaQuery(AgentPropertiesDO.class)
                .eq(AgentPropertiesDO::getAgentName, name)
                .eq(AgentPropertiesDO::getDelFlag, 0);
        AgentPropertiesDO agentPropertiesDO = baseMapper.selectOne(queryWrapper);
        // （2）存在时复制属性并填充标签
        AgentPropertiesRespDTO result = new AgentPropertiesRespDTO();
        if (agentPropertiesDO != null) {
            BeanUtils.copyProperties(agentPropertiesDO, result);
            // 填充标签数据
            result.setTags(getAgentTags(agentPropertiesDO.getId()));
        }
        return result;
    }

    /**
     * 分页查询智能体配置，并逐条填充标签。
     */
    @Override
    public PageInfo<AgentPropertiesRespDTO> getByPage(AgentPropertiesReqDTO requestParam) {
        // （1）构建分页参数与查询条件（按创建时间倒序）
        Page<AgentPropertiesDO> page = new Page<>(requestParam.getPageNum(), requestParam.getPageSize());
        LambdaQueryWrapper<AgentPropertiesDO> queryWrapper = Wrappers.lambdaQuery(AgentPropertiesDO.class)
                .eq(AgentPropertiesDO::getDelFlag, 0)
                .orderByDesc(AgentPropertiesDO::getCreateTime);
        Page<AgentPropertiesDO> agentPropertiesDOPage = baseMapper.selectPage(page, queryWrapper);
        // （2）逐条转换并填充标签
        List<AgentPropertiesRespDTO> resultList = agentPropertiesDOPage.getRecords().stream()
                .map(item -> {
                    AgentPropertiesRespDTO respDTO = new AgentPropertiesRespDTO();
                    BeanUtils.copyProperties(item, respDTO);
                    // 填充标签数据
                    respDTO.setTags(getAgentTags(item.getId()));
                    return respDTO;
                })
                .collect(Collectors.toList());
        // （3）组装分页信息返回
        PageInfo<AgentPropertiesRespDTO> pageInfo = new PageInfo<>();
        pageInfo.setRecords(resultList);
        pageInfo.setTotal(agentPropertiesDOPage.getTotal());
        pageInfo.setCurrent(agentPropertiesDOPage.getCurrent());
        pageInfo.setPages(agentPropertiesDOPage.getPages());
        pageInfo.setSize(agentPropertiesDOPage.getSize());
        return pageInfo;
    }

    /**
     * 创建智能体标签
     */
    private void createAgentTags(Long agentId, List<Integer> tagCodes) {
        // （1）按标签码解析枚举并组装标签实体
        List<AgentTag> agentTags = new ArrayList<>();
        for (Integer tagCode : tagCodes) {
            AgentTagType tagType = AgentTagType.getByCode(tagCode);
            if (tagType != null) {
                AgentTag agentTag = new AgentTag();
                agentTag.setAgentId(agentId);
                agentTag.setTagName(tagType.getName());
                agentTag.setDescription(tagType.getName() + "标签");
                agentTag.setCreateTime(new Date());
                agentTag.setUpdateTime(new Date());
                agentTag.setDelFlag(0);
                agentTags.add(agentTag);
            }
        }
        // （2）批量保存标签
        if (!agentTags.isEmpty()) {
            agentTagService.saveBatch(agentTags);
        }
    }

    /**
     * 更新智能体标签
     */
    private void updateAgentTags(Long agentId, List<Integer> tagCodes) {
        // 先删除原有标签
        LambdaUpdateWrapper<AgentTag> deleteWrapper = Wrappers.lambdaUpdate(AgentTag.class)
                .eq(AgentTag::getAgentId, agentId)
                .set(AgentTag::getDelFlag, 1);
        agentTagService.update(deleteWrapper);
        
        // 创建新标签
        if (!tagCodes.isEmpty()) {
            createAgentTags(agentId, tagCodes);
        }
    }

    /**
     * 获取智能体标签
     */
    private List<AgentPropertiesRespDTO.TagInfo> getAgentTags(Long agentId) {
        LambdaQueryWrapper<AgentTag> queryWrapper = Wrappers.lambdaQuery(AgentTag.class)
                .eq(AgentTag::getAgentId, agentId)
                .eq(AgentTag::getDelFlag, 0);
        List<AgentTag> agentTags = agentTagService.list(queryWrapper);
        
        return agentTags.stream().map(tag -> {
            AgentPropertiesRespDTO.TagInfo tagInfo = new AgentPropertiesRespDTO.TagInfo();
            // 根据标签名称获取对应的枚举
            AgentTagType tagType = AgentTagType.getByName(tag.getTagName());
            if (tagType != null) {
                tagInfo.setCode(tagType.getCode());
                tagInfo.setName(tagType.getName());
            } else {
                // 如果找不到对应枚举，使用默认值
                tagInfo.setCode(11); // 其他
                tagInfo.setName(tag.getTagName());
                tagInfo.setColor("#95a5a6");
            }
            return tagInfo;
        }).collect(Collectors.toList());
    }

    /**
     * 查询最新创建的 10 个智能体配置。
     */
    @Override
    public List<AgentPropertiesDO> listTop10() {
        LambdaQueryWrapper<AgentPropertiesDO> queryWrapper = Wrappers.lambdaQuery(AgentPropertiesDO.class)
                .eq(AgentPropertiesDO::getDelFlag, 0)
                .orderByDesc(AgentPropertiesDO::getCreateTime)
                .last("limit 10"); // Limit to top 10
        return baseMapper.selectList(queryWrapper);
    }

    /**
     * 查询所有未删除的智能体配置，按创建时间倒序。
     */
    @Override
    public List<AgentPropertiesDO> listActiveAgents() {
        LambdaQueryWrapper<AgentPropertiesDO> queryWrapper = Wrappers.lambdaQuery(AgentPropertiesDO.class)
                .eq(AgentPropertiesDO::getDelFlag, 0)
                .orderByDesc(AgentPropertiesDO::getCreateTime);
        return baseMapper.selectList(queryWrapper);
    }
}





