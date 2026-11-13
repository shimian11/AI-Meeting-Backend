-- ============================================================
-- schema.sql · 全量建表（12 张）
-- 开发阶段主文件：新环境一条命令建完全部表
-- 执行顺序：整个文件一次性执行（文件内无表间依赖，可乱序）
-- 原则：只包含 CREATE TABLE / CREATE INDEX，不含 INSERT
-- ============================================================

SET NAMES utf8mb4;

-- ============================================================
-- 用户域
-- ============================================================

-- 用户表：全系统身份锚点（username = 邮箱，登录态 Redis key、各表 created_by 均挂它）
CREATE TABLE t_user
(
    id            BIGINT AUTO_INCREMENT COMMENT 'ID'
        PRIMARY KEY,
    username      VARCHAR(256) NULL COMMENT '用户名（值=邮箱，登录身份）',
    password      VARCHAR(512) NULL COMMENT '密码（BCrypt 哈希，2026-11-11 起禁明文）',
    real_name     VARCHAR(256) NULL COMMENT '真实姓名',
    phone         VARCHAR(128) NULL COMMENT '手机号（预留）',
    mail          VARCHAR(512) NULL COMMENT '邮箱（登录凭据，唯一）',
    deletion_time BIGINT       NULL COMMENT '注销时间戳',
    create_time   DATETIME     NULL COMMENT '创建时间',
    update_time   DATETIME     NULL COMMENT '修改时间',
    del_flag      TINYINT(1)   NULL COMMENT '删除标识 0：未删除 1：已删除',
    CONSTRAINT idx_unique_username UNIQUE (username)
);

CREATE UNIQUE INDEX uk_mail ON t_user (mail);

-- 管理员权限表：权限是运营属性，独立于用户表（一人一行）
CREATE TABLE admin_permission
(
    id          BIGINT AUTO_INCREMENT COMMENT 'ID'
        PRIMARY KEY,
    user_id     BIGINT               NOT NULL COMMENT '用户ID',
    username    VARCHAR(256)         NOT NULL COMMENT '用户名（值=邮箱）',
    is_admin    TINYINT(1) DEFAULT 0 NULL COMMENT '是否管理员 0：普通用户 1：管理员（7C4 将扩三值角色）',
    create_time DATETIME             NULL COMMENT '创建时间',
    update_time DATETIME             NULL COMMENT '修改时间',
    del_flag    TINYINT(1) DEFAULT 0 NULL COMMENT '删除标识 0：未删除 1：已删除',
    CONSTRAINT idx_unique_user_id
        UNIQUE (user_id)
);

CREATE INDEX idx_is_admin ON admin_permission (is_admin);

CREATE INDEX idx_username ON admin_permission (username);

-- ============================================================
-- Agent 域
-- ============================================================

-- 智能体配置表：每个 Agent 一行，行为由数据驱动
-- 智能体配置表
-- 2026-11-13（ADR-017）：删除 api_secret / api_key / api_flow_id / agent_type 四列。
--   前三个是讯飞星辰遗留（只写不读，且 apiKey 曾随查询接口明文返回）；
--   agent_type 是双路由开关，星辰通道已整体下线，开关无意义。
-- 凭证统一存 .env（BANMA_DEEPSEEK_API_KEY 等），本表不存任何密钥。
CREATE TABLE agent_properties
(
    id          BIGINT AUTO_INCREMENT COMMENT 'ID'
        PRIMARY KEY,
    agent_name  VARCHAR(256) NULL COMMENT '智能体名称',
    create_time DATETIME     NULL COMMENT '创建时间',
    update_time DATETIME     NULL COMMENT '修改时间',
    del_flag    TINYINT(1)   NULL COMMENT '删除标识 0：未删除 1：已删除'
);

-- 智能体标签表：控制台筛选用
CREATE TABLE agent_tag
(
    id          BIGINT AUTO_INCREMENT COMMENT 'ID'
        PRIMARY KEY,
    tag_name    VARCHAR(256)         NOT NULL COMMENT '标签名称',
    agent_id    BIGINT               NOT NULL COMMENT '关联的智能体ID',
    description VARCHAR(512)         NULL COMMENT '标签描述',
    create_time DATETIME             NULL COMMENT '创建时间',
    update_time DATETIME             NULL COMMENT '修改时间',
    del_flag    TINYINT(1) DEFAULT 0 NULL COMMENT '删除标识 0：未删除 1：已删除'
);

CREATE INDEX idx_agent_id ON agent_tag (agent_id);

CREATE INDEX idx_create_time ON agent_tag (create_time);

CREATE INDEX idx_tag_name ON agent_tag (tag_name);

-- 上传文件资产表：文件本体在外部存储，本表只存元数据 + 访问地址
CREATE TABLE agent_file_asset
(
    id              BIGINT AUTO_INCREMENT COMMENT '主键ID'
        PRIMARY KEY,
    agent_id        BIGINT                                NOT NULL COMMENT '智能体ID',
    session_id      VARCHAR(64)                           NULL COMMENT '会话ID',
    user_name       VARCHAR(64)                           NOT NULL COMMENT '上传用户名（值=邮箱）',
    biz_type        VARCHAR(32)  DEFAULT 'general'        NOT NULL COMMENT '业务类型：resume/kb/audio',
    source_platform VARCHAR(32)  DEFAULT 'minio'          NOT NULL COMMENT '来源平台：minio（讯飞已下线）',
    file_name       VARCHAR(255)                         NOT NULL COMMENT '原始文件名',
    file_ext        VARCHAR(32)                          NULL COMMENT '文件扩展名',
    content_type    VARCHAR(128)                         NULL COMMENT 'MIME类型',
    file_size       BIGINT      DEFAULT 0                NOT NULL COMMENT '文件大小（字节）',
    file_url        VARCHAR(1024)                        NOT NULL COMMENT '访问URL（预签名，15分钟有效）',
    minio_key       VARCHAR(512)                         NULL COMMENT 'MinIO 对象键（永久有效，重灌/删除依据，6G 填充）',
    upload_status   TINYINT     DEFAULT 1                NOT NULL COMMENT '上传状态：1成功 2失败',
    remark          VARCHAR(255)                         NULL COMMENT '备注',
    create_time     DATETIME    DEFAULT CURRENT_TIMESTAMP NULL COMMENT '创建时间',
    update_time     DATETIME    DEFAULT CURRENT_TIMESTAMP NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    del_flag        TINYINT     DEFAULT 0                NOT NULL COMMENT '删除标识：0未删除 1已删除'
) COMMENT '上传文件资产表' COLLATE = utf8mb4_unicode_ci;

CREATE INDEX idx_agent_id ON agent_file_asset (agent_id);

CREATE INDEX idx_create_time ON agent_file_asset (create_time);

CREATE INDEX idx_session_id ON agent_file_asset (session_id);

CREATE INDEX idx_user_name ON agent_file_asset (user_name);

-- ============================================================
-- 面试域
-- ============================================================

-- 面试记录表：状态机 INIT/IN_PROGRESS/FINISHED/EVALUATED；快照 JSON 避免报告页多表 join
CREATE TABLE interview_record
(
    id                    BIGINT AUTO_INCREMENT COMMENT '主键ID'
        PRIMARY KEY,
    user_id               BIGINT                             NOT NULL COMMENT '用户ID',
    session_id            VARCHAR(64)                        NOT NULL COMMENT '会话ID',
    interview_score       INT                                NULL COMMENT '面试得分',
    resume_score          INT                                NULL COMMENT '简历得分',
    interview_status      VARCHAR(32)                        NULL COMMENT '面试状态: INIT/IN_PROGRESS/FINISHED/EVALUATED',
    question_count        INT                                NULL COMMENT '面试题数量',
    interviewer_agent_id  BIGINT                             NULL COMMENT '面试官Agent ID',
    interview_suggestions TEXT                               NULL COMMENT '面试建议',
    interview_direction   VARCHAR(128)                       NULL COMMENT '面试方向',
    start_time            DATETIME                           NULL COMMENT '开始时间',
    end_time              DATETIME                           NULL COMMENT '结束时间',
    duration_seconds      INT                                NULL COMMENT '面试时长(秒)',
    session_snapshot_json LONGTEXT                           NULL COMMENT '会话快照JSON（报告页一次读全量）',
    create_time           DATETIME DEFAULT CURRENT_TIMESTAMP NULL COMMENT '创建时间',
    update_time           DATETIME DEFAULT CURRENT_TIMESTAMP NULL ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    del_flag              TINYINT  DEFAULT 0                 NOT NULL COMMENT '删除标记: 0未删除 1已删除'
) COMMENT '面试记录表' COLLATE = utf8mb4_unicode_ci;

CREATE INDEX idx_create_time ON interview_record (create_time);

CREATE UNIQUE INDEX uk_interview_record_user_session_del
    ON interview_record (user_id, session_id, del_flag);

CREATE INDEX idx_session_id ON interview_record (session_id);

-- AI 配置表：一行 = 一个模型接入点（Java 直调场景，7A 追问链用）
CREATE TABLE ai_properties
(
    id                     BIGINT AUTO_INCREMENT COMMENT 'ID'
        PRIMARY KEY,
    ai_name                VARCHAR(256)               NOT NULL COMMENT 'AI名称',
    ai_type                VARCHAR(64)                NOT NULL COMMENT 'AI类型：deepseek、openai 等',
    api_key                VARCHAR(512)               NOT NULL COMMENT 'API密钥',
    api_secret             VARCHAR(512)               NULL COMMENT 'API密钥（部分AI需要）',
    api_url                VARCHAR(512)               NULL COMMENT 'API地址',
    model_name             VARCHAR(256)               NULL COMMENT '模型名称',
    max_tokens             INT           DEFAULT 4096 NULL COMMENT '最大token数',
    temperature            DECIMAL(3, 2) DEFAULT 0.70 NULL COMMENT '温度参数',
    system_prompt          TEXT                       NULL COMMENT '系统提示词（控制台可调，免发版）',
    is_enabled             TINYINT(1)    DEFAULT 1    NULL COMMENT '是否启用 0：禁用 1：启用',
    enable_thinking        TINYINT(1)    DEFAULT 0    NULL COMMENT '是否开启思考模式（DeepSeek专用，ADR-014 显式关思考）',
    thinking_budget_tokens INT                        NULL COMMENT '思考模式预算Token数（DeepSeek专用）',
    create_time            DATETIME                   NULL COMMENT '创建时间',
    update_time            DATETIME                   NULL COMMENT '修改时间',
    del_flag               TINYINT(1)    DEFAULT 0    NULL COMMENT '删除标识 0：未删除 1：已删除',
    project_id             VARCHAR(255)               NULL COMMENT '项目ID',
    organization_id        VARCHAR(255)               NULL COMMENT '组织ID'
) COMMENT 'AI配置表';

CREATE INDEX idx_ai_type ON ai_properties (ai_type);

CREATE INDEX idx_create_time ON ai_properties (create_time);

-- AI 消息媒体附件表：会话中图片/文件/音频的元数据
CREATE TABLE ai_message_media
(
    id           BIGINT AUTO_INCREMENT COMMENT 'ID'
        PRIMARY KEY,
    session_id   VARCHAR(64)                 NOT NULL COMMENT '会话ID',
    message_seq  INT                         NOT NULL COMMENT '消息序号',
    media_type   VARCHAR(32) DEFAULT 'image' NOT NULL COMMENT '媒体类型：image, file, audio',
    media_url    VARCHAR(1024)               NOT NULL COMMENT '媒体URL',
    file_name    VARCHAR(256)                NULL COMMENT '原始文件名',
    file_size    BIGINT                      NULL COMMENT '文件大小（字节）',
    content_type VARCHAR(128)                NULL COMMENT '文件MIME类型',
    create_time  DATETIME                    NULL COMMENT '创建时间'
) COMMENT 'AI消息媒体附件表';

CREATE INDEX idx_session_seq ON ai_message_media (session_id, message_seq);

-- ============================================================
-- 题库域（6G）
-- ============================================================

-- 题库文档表：一份上传文件 = 一行；doc_id 同时是 Qdrant payload 锚点（跨库逻辑关联）
CREATE TABLE kb_document
(
    id          BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    doc_id      VARCHAR(64)  NOT NULL COMMENT '稳定标识，Python 侧 point id 前半',
    doc_name    VARCHAR(255) NOT NULL COMMENT '展示名（原文件名）',
    direction   VARCHAR(16)  NOT NULL COMMENT '方向：后端开发/前端开发/物联网开发/大数据',
    file_size   BIGINT       NOT NULL COMMENT '字节数',
    file_hash   VARCHAR(64)  NOT NULL COMMENT 'SHA-256',
    minio_key   VARCHAR(512) NOT NULL COMMENT 'MinIO 对象键（预签名URL 15分钟过期，重灌/删除靠它）',
    chunk_total INT          NOT NULL DEFAULT 0 COMMENT '切块数（Python 回传）',
    status      VARCHAR(16)  NOT NULL DEFAULT 'UPLOADED' COMMENT 'UPLOADED/INGESTING/ACTIVE/FAILED',
    created_by  VARCHAR(64)  NOT NULL COMMENT '上传人（username=邮箱）',
    create_time DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time DATETIME     NULL,
    del_flag    TINYINT      NOT NULL DEFAULT 0,
    PRIMARY KEY (id),
    UNIQUE KEY uk_doc_id (doc_id),
    UNIQUE KEY uk_file_hash (file_hash, direction) COMMENT '同方向同文件不可重复入库（ADR-016 幂等，DB 层兜底）'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='题库文档（6G）';

-- 题库入库任务表：一次 ingest 调用 = 一行；与文档分表（文档长期/任务单次，可多次重试）
CREATE TABLE kb_ingest_job
(
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    job_id      VARCHAR(64) NOT NULL COMMENT '前端轮询键',
    doc_id      VARCHAR(64) NOT NULL COMMENT '指向 kb_document',
    status      VARCHAR(16) NOT NULL DEFAULT 'QUEUED' COMMENT 'QUEUED/RUNNING/SUCCESS/FAILED/INTERRUPTED',
    error_code  VARCHAR(32) NULL COMMENT '失败原因（Python 错误码直传）',
    points      INT         NULL COMMENT '成功写入块数（Python 回传）',
    duration_ms INT         NULL COMMENT 'Python 处理耗时',
    create_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finish_time DATETIME    NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_job_id (job_id),
    KEY idx_doc (doc_id),
    KEY idx_status (status) COMMENT '启动扫描 WHERE status=RUNNING 做崩溃恢复（ADR-016）'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='题库入库任务（6G，异步 job）';

-- ============================================================
-- 治理域（6G 审计 / 6F 配额）
-- ============================================================

-- 控制台审计日志表：反范式设计——用户删了日志还在（审计价值恰在于此）
CREATE TABLE admin_audit_log
(
    id          BIGINT        NOT NULL AUTO_INCREMENT COMMENT '主键',
    username    VARCHAR(64)   NOT NULL COMMENT '操作人（username=邮箱，快照不跟随用户改名）',
    action      VARCHAR(32)   NOT NULL COMMENT '动作：KB_UPLOAD/KB_DELETE/AGENT_SWITCH/...',
    target      VARCHAR(128)  NOT NULL COMMENT '对象标识（doc_id / agent_id 等）',
    detail      VARCHAR(1024) NULL COMMENT '补充信息（JSON，不同 action 结构不同）',
    create_time DATETIME      NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    KEY idx_user_time (username, create_time) COMMENT '控制台高频查询：我最近的操作'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='控制台审计日志（6G）';

-- 用户用量配额表：按日聚合（流水在 Python trace，不重复记账）；双配额=次数防滥用+token防成本失控
CREATE TABLE agent_usage_quota
(
    id          BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    username    VARCHAR(64) NOT NULL COMMENT '用户（username=邮箱）',
    quota_date  DATE        NOT NULL COMMENT '自然日',
    call_count  INT         NOT NULL DEFAULT 0 COMMENT '当日调用次数',
    token_count INT         NOT NULL DEFAULT 0 COMMENT '当日 token 消耗',
    update_time DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (id),
    UNIQUE KEY uk_user_date (username, quota_date) COMMENT '每人每天一行；计数走 ON DUPLICATE KEY UPDATE 原子累加'
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT ='用户用量配额（6F，按日聚合）';
