package com.banma.interview.interview.application;

import cn.hutool.core.util.StrUtil;
import com.banma.interview.common.convention.exception.ClientException;
import com.banma.interview.common.enums.InterviewErrorCodeEnum;
import com.banma.interview.interview.dao.entity.InterviewSession;
import com.banma.interview.interview.dao.repository.InterviewSessionRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * 面试会话归属校验服务：校验会话存在且属于当前用户。
 */
@Component
@RequiredArgsConstructor
public class InterviewSessionOwnershipService {

    /** 面试会话仓储（Mongo） */
    private final InterviewSessionRepository interviewSessionRepository;

    /**
     * 校验会话 id、用户 id 及会话归属，不满足时抛出业务异常。
     *
     * @param sessionId 会话 id
     * @param userId 用户 id
     * @return 归属当前用户的面试会话
     */
    public InterviewSession requireOwnedSession(String sessionId, Long userId) {
        // （1）校验会话 id 与用户 id 是否合法
        if (StrUtil.isBlank(sessionId)) {
            throw new ClientException(InterviewErrorCodeEnum.SESSION_ID_EMPTY);
        }
        if (userId == null || userId <= 0) {
            throw new ClientException(InterviewErrorCodeEnum.INVALID_USER_ID);
        }
        // （2）查询未删除的面试会话
        InterviewSession session = interviewSessionRepository.findBySessionIdAndDelFlag(sessionId, 0)
                .orElseThrow(() -> new ClientException(InterviewErrorCodeEnum.INTERVIEW_SESSION_NOT_FOUND));
        // （3）校验会话是否属于当前用户
        if (!userId.equals(session.getUserId())) {
            throw new ClientException(InterviewErrorCodeEnum.INTERVIEW_SESSION_ACCESS_DENIED);
        }
        return session;
    }
}
