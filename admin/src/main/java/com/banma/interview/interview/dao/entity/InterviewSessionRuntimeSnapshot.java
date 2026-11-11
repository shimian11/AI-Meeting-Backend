package com.banma.interview.interview.dao.entity;

import com.banma.interview.interview.api.io.req.DemeanorScoreDTO;
import com.banma.interview.interview.service.model.InterviewFlowState;
import com.banma.interview.interview.service.model.InterviewRuntimeConfidence;
import com.banma.interview.interview.service.model.InterviewRuntimeScoreAggregate;
import com.banma.interview.interview.service.model.InterviewTurnLog;
import lombok.Data;

import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * 定义面试会话运行态快照持久化实体，
 * 用于保存会话恢复所需的题目、流程、得分聚合、追问和最近轮次等关键状态。
 */
@Data
public class InterviewSessionRuntimeSnapshot {

    /** 主键ID */
    private String id;

    /** 会话ID */
    private String sessionId;

    /** 用户ID */
    private Long userId;

    /** 会话状态 */
    private String sessionStatus;

    /** 快照版本号 */
    private Long snapshotVersion;

    /** 快照级别（如完整快照/增量快照） */
    private String snapshotLevel;

    /** 重建置信度等级 */
    private InterviewRuntimeConfidence rebuildConfidence;

    /** 快照更新时间 */
    private Date snapshotUpdatedAt;

    /** 简历文件URL */
    private String resumeFileUrl;

    /** 面试类型 */
    private String interviewType;

    /** 面试方向/岗位方向 */
    private String direction;

    /** 题目Map（题号 → 题目内容） */
    private Map<String, String> questions;

    /** 建议Map（题号 → 建议内容） */
    private Map<String, String> suggestions;

    /** 简历上下文（结构化简历数据） */
    private Map<String, Object> resumeContext;

    /** 简历得分 */
    private Integer resumeScore;

    /** 仪态得分 */
    private Integer demeanorScore;

    /** 仪态评分详情 */
    private DemeanorScoreDTO demeanorDetails;

    /** 面试流程状态 */
    private InterviewFlowState flow;

    /** 得分聚合 */
    private InterviewRuntimeScoreAggregate scoreAggregate;

    /** 追问Map（题号 → 追问内容） */
    private Map<String, String> followUpQuestions;

    /** 最近轮次日志列表 */
    private List<InterviewTurnLog> recentTurns;

    /** 最近轮次数量 */
    private Integer recentTurnCount;

    /** 归档水位线 */
    private Long archiveWatermark;

    /** 最后轮次序号 */
    private Long lastTurnSeq;

    /** 最后应用的请求ID（幂等去重） */
    private String lastAppliedRequestId;

    /** 最后变更ID */
    private String lastMutationId;

    /** 最后变更时间 */
    private Date lastMutationTime;

    /** 最后提交的题号 */
    private String lastCommittedQuestionNumber;

    /** 最后提交的轮次摘要（用于去重比对） */
    private String lastCommittedTurnDigest;

    /** 材料版本号（题目/建议材料版本） */
    private Long materialVersion;

    /** 创建时间 */
    private Date createTime;

    /** 更新时间 */
    private Date updateTime;
}
