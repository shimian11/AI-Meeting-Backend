package com.banma.interview.interview.dao.entity;

import lombok.Data;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.util.Date;

@Data
@Document(collection = "interview_session")
public class InterviewSession {

    /** 文档主键ID（MongoDB 文档唯一标识） */
    @Id
    private String id;

    /** 会话ID（业务唯一标识，同一场面试的全局编号） */
    @Indexed(unique = true)
    private String sessionId;

    /** 用户ID（本场面试所属用户） */
    @Indexed
    private Long userId;

    /** 会话状态（如进行中、已完成、已关闭等） */
    @Indexed
    private String status;

    /** 会话标题（通常由首条消息或面试岗位自动生成） */
    private String conversationTitle;

    /** 面试官智能体ID（负责本场面试出题与追问的智能体） */
    private Long interviewerAgentId;

    /** 简历文件访问URL（本场面试使用的候选人简历） */
    private String resumeFileUrl;

    /** 面试类型（如 backend、frontend 等大类） */
    private String interviewType;

    /** 面试开始时间 */
    private Date startTime;

    /** 面试结束时间 */
    private Date endTime;

    /** 创建时间（文档首次写入时间） */
    @CreatedDate
    private Date createTime;

    /** 更新时间（文档最近一次修改时间） */
    @LastModifiedDate
    private Date updateTime;

    /** 删除标识 0：未删除 1：已删除 */
    private Integer delFlag;
}
