package com.banma.interview.interview.dao.entity;

import com.banma.interview.interview.service.model.InterviewTurnLog;
import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;

/**
 * 定义面试会话轮次归档持久化实体，
 * 用于保存每次答题提交后的轮次回放数据并支撑会话重建与软回放幂等。
 */
@Data
@Document(collection = "interview_session_turn_archive")
public class InterviewSessionTurnArchive {

    /** 文档主键ID（MongoDB 文档唯一标识） */
    @Id
    private String id;

    /** 会话ID（该轮次所属的面试会话） */
    @Indexed
    private String sessionId;

    /** 答题请求ID（用于幂等去重，避免同一请求重复归档） */
    @Indexed
    private String requestId;

    /** 轮次序号（同一会话内单调递增，标识第几轮问答） */
    @Indexed
    private Long seq;

    /** 归档时对应的运行态快照版本号 */
    private Long snapshotVersion;

    /** 轮次回放数据（该轮的题目、回答、评分与流程状态等） */
    private InterviewTurnLog turnPayload;

    /** 归档创建时间 */
    @CreatedDate
    private Date createdAt;
}
