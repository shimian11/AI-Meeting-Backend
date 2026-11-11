package com.banma.interview.interview.service.impl;

import cn.hutool.core.util.IdUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.banma.interview.agent.application.BusinessAgentResolver;
import com.banma.interview.agent.application.BusinessAgentScene;
import com.banma.interview.interview.api.io.req.InterviewConversationPageReqDTO;
import com.banma.interview.interview.api.io.resp.InterviewConversationRespDTO;
import com.banma.interview.interview.api.io.resp.InterviewSessionCreateRespDTO;
import com.banma.interview.interview.application.runtime.InterviewSessionRuntimeSnapshotService;
import com.banma.interview.interview.application.InterviewSessionOwnershipService;
import com.banma.interview.interview.dao.entity.InterviewSession;
import com.banma.interview.interview.dao.repository.InterviewSessionRepository;
import com.banma.interview.interview.service.InterviewSessionService;
import com.banma.interview.interview.service.model.InterviewSessionStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.BeanUtils;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 面试会话服务实现：负责会话创建、状态流转、归属校验与分页查询。
 */
@Service
@RequiredArgsConstructor
public class InterviewSessionServiceImpl implements InterviewSessionService {

    /** 面试会话仓储（Mongo） */
    private final InterviewSessionRepository interviewSessionRepository;
    /** 会话归属校验（防越权） */
    private final InterviewSessionOwnershipService ownershipService;
    /** 按业务场景解析智能体配置（模型路由入口） */
    private final BusinessAgentResolver businessAgentResolver;
    /** 运行态快照服务的延迟提供者（破循环依赖） */
    private final ObjectProvider<InterviewSessionRuntimeSnapshotService> runtimeSnapshotServiceProvider;

    /**
     * 创建面试会话：先废弃用户的活跃会话，再初始化草稿态会话与运行时快照。
     *
     * @param userId 用户 id
     * @return 新建会话的 id 与状态
     */
    @Override
    public InterviewSessionCreateRespDTO createSession(Long userId) {
        // （1）废弃该用户已有的活跃会话
        abandonActiveSessions(userId);

        // （2）构造草稿态会话并绑定提问用智能体
        InterviewSession session = new InterviewSession();
        session.setSessionId(IdUtil.getSnowflakeNextIdStr());
        session.setUserId(userId);
        session.setStatus(InterviewSessionStatus.DRAFT.name());
        session.setConversationTitle("Interview Session");
        session.setInterviewerAgentId(
                businessAgentResolver.resolveRequired(BusinessAgentScene.INTERVIEW_QUESTION_ASKING).getId());
        session.setDelFlag(0);
        // （3）保存会话，并在可用时初始化运行时快照
        interviewSessionRepository.save(session);
        InterviewSessionRuntimeSnapshotService runtimeSnapshotService = runtimeSnapshotServiceProvider.getIfAvailable();
        if (runtimeSnapshotService != null) {
            runtimeSnapshotService.initializeDraftSnapshot(session);
        }
        return new InterviewSessionCreateRespDTO(session.getSessionId(), session.getStatus());
    }

    /**
     * 分页查询用户的面试会话列表。
     *
     * @param userId 用户 id
     * @param requestParam 分页与筛选参数
     * @return 会话分页结果
     */
    @Override
    public IPage<InterviewConversationRespDTO> pageConversations(Long userId, InterviewConversationPageReqDTO requestParam) {
        // （1）按页码构造分页参数并按条件查询
        Pageable pageable = PageRequest.of(requestParam.getCurrent() - 1, requestParam.getSize());
        org.springframework.data.domain.Page<InterviewSession> sessionPage = queryPage(userId, requestParam, pageable);
        // （2）组装分页结果并转换记录
        Page<InterviewConversationRespDTO> result = new Page<>(requestParam.getCurrent(), requestParam.getSize());
        result.setTotal(sessionPage.getTotalElements());
        result.setRecords(sessionPage.getContent().stream().map(this::toRespDTO).collect(Collectors.toList()));
        return result;
    }

    /**
     * 按会话 id 查询未删除的会话，不存在时返回 null。
     */
    @Override
    public InterviewSession getBySessionId(String sessionId) {
        return interviewSessionRepository.findBySessionIdAndDelFlag(sessionId, 0).orElse(null);
    }

    /**
     * 校验会话归属并返回会话，不满足时抛出业务异常。
     */
    @Override
    public InterviewSession requireOwnedSession(String sessionId, Long userId) {
        return ownershipService.requireOwnedSession(sessionId, userId);
    }

    /**
     * 将会话状态标记为简历上传中。
     */
    @Override
    public void markResumeUploading(String sessionId, Long userId) {
        // （1）校验归属后更新状态并保存
        InterviewSession session = requireOwnedSession(sessionId, userId);
        session.setStatus(InterviewSessionStatus.RESUME_UPLOADING.name());
        interviewSessionRepository.save(session);
    }

    /**
     * 将会话标记为就绪，并写入简历文件地址与面试方向。
     */
    @Override
    public void markReady(String sessionId, Long userId, String resumeFileUrl, String interviewType) {
        // （1）校验归属后更新状态、简历地址与面试方向
        InterviewSession session = requireOwnedSession(sessionId, userId);
        session.setStatus(InterviewSessionStatus.READY.name());
        session.setResumeFileUrl(resumeFileUrl);
        session.setInterviewType(interviewType);
        interviewSessionRepository.save(session);
    }

    /**
     * 将会话状态回退为草稿。
     */
    @Override
    public void markDraft(String sessionId, Long userId) {
        // （1）校验归属后更新为草稿状态并保存
        InterviewSession session = requireOwnedSession(sessionId, userId);
        session.setStatus(InterviewSessionStatus.DRAFT.name());
        interviewSessionRepository.save(session);
    }

    /**
     * 会话处于就绪状态时切换为进行中，并记录开始时间。
     */
    @Override
    public void markInProgressIfReady(String sessionId, Long userId) {
        // （1）校验归属，仅就绪状态才允许流转
        InterviewSession session = requireOwnedSession(sessionId, userId);
        if (!InterviewSessionStatus.READY.name().equals(session.getStatus())) {
            return;
        }
        // （2）更新为进行中，并补齐开始时间
        session.setStatus(InterviewSessionStatus.IN_PROGRESS.name());
        if (session.getStartTime() == null) {
            session.setStartTime(new Date());
        }
        interviewSessionRepository.save(session);
    }

    /**
     * 结束面试会话：置为已结束并记录结束时间。
     */
    @Override
    public void finishSession(String sessionId, Long userId) {
        // （1）校验归属并更新为已结束状态
        InterviewSession session = requireOwnedSession(sessionId, userId);
        session.setStatus(InterviewSessionStatus.FINISHED.name());
        // （2）补齐开始时间与结束时间
        if (session.getStartTime() == null) {
            session.setStartTime(session.getCreateTime() == null ? new Date() : session.getCreateTime());
        }
        session.setEndTime(new Date());
        interviewSessionRepository.save(session);
    }

    /**
     * 废弃用户所有活跃会话（草稿、上传中、就绪、进行中）。
     *
     * @param userId 用户 id
     */
    @Override
    public void abandonActiveSessions(Long userId) {
        // （1）查询该用户所有活跃状态的会话
        List<InterviewSession> sessions = interviewSessionRepository.findByUserIdAndStatusInAndDelFlagOrderByUpdateTimeDesc(
                userId,
                List.of(
                        InterviewSessionStatus.DRAFT.name(),
                        InterviewSessionStatus.RESUME_UPLOADING.name(),
                        InterviewSessionStatus.READY.name(),
                        InterviewSessionStatus.IN_PROGRESS.name()
                ),
                0
        );
        if (sessions == null || sessions.isEmpty()) {
            return;
        }
        // （2）逐个置为废弃状态并记录结束时间后批量保存
        for (InterviewSession session : sessions) {
            session.setStatus(InterviewSessionStatus.ABANDONED.name());
            session.setEndTime(new Date());
        }
        interviewSessionRepository.saveAll(sessions);
    }

    /**
     * 按关键字与状态组合条件分页查询用户会话。
     */
    private org.springframework.data.domain.Page<InterviewSession> queryPage(
            Long userId,
            InterviewConversationPageReqDTO requestParam,
            Pageable pageable) {
        // （1）有关键字时按标题模糊匹配，并可叠加状态过滤
        if (StrUtil.isNotBlank(requestParam.getKeyword())) {
            String keyword = requestParam.getKeyword().trim();
            if (StrUtil.isNotBlank(requestParam.getStatus())) {
                return interviewSessionRepository.findByUserIdAndStatusAndDelFlagAndTitleContaining(
                        userId,
                        requestParam.getStatus().trim(),
                        0,
                        keyword,
                        pageable
                );
            }
            return interviewSessionRepository.findByUserIdAndDelFlagAndTitleContaining(userId, 0, keyword, pageable);
        }
        // （2）无关键字时仅按状态过滤
        if (StrUtil.isNotBlank(requestParam.getStatus())) {
            return interviewSessionRepository.findByUserIdAndStatusAndDelFlagOrderByUpdateTimeDesc(
                    userId,
                    requestParam.getStatus().trim(),
                    0,
                    pageable
            );
        }
        // （3）无筛选条件时查询用户全部会话，按更新时间倒序
        return interviewSessionRepository.findByUserIdAndDelFlagOrderByUpdateTimeDesc(userId, 0, pageable);
    }

    /**
     * 将会话实体转换为会话响应 DTO。
     */
    private InterviewConversationRespDTO toRespDTO(InterviewSession session) {
        // （1）复制会话字段生成响应 DTO
        InterviewConversationRespDTO respDTO = new InterviewConversationRespDTO();
        BeanUtils.copyProperties(session, respDTO);
        return respDTO;
    }
}
