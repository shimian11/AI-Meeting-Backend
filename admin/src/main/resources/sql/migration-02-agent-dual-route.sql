-- ============================================================
-- migration-02-agent-dual-route.sql · Agent 双路由
-- 阶段：6A（BanmaAgentClient 落码前）
-- 说明：全量场景（schema.sql 建表）agent_type 列已含在内，
--       本文件仅供"老库增量"场景执行 ALTER + UPDATE
-- ============================================================

-- ① 加 agent_type 列（6C 双路由数据开关）
ALTER TABLE agent_properties
    ADD COLUMN agent_type VARCHAR(16) NOT NULL DEFAULT 'XINGCHEN'
    COMMENT '调用链类型：PYTHON | XINGCHEN';

-- ② 存量校准：讯飞真凭证已被 .env 取代，改占位
UPDATE agent_properties
SET api_key = 'PLACEHOLDER-MOVED-TO-ENV',
    api_secret = 'PLACEHOLDER-MOVED-TO-ENV';

-- ============================================================
-- 验证
-- ============================================================
-- DESC agent_properties;
--   应出现 agent_type 列，Default = XINGCHEN
-- SELECT id, agent_name, agent_type FROM agent_properties;
--   key/secret 应为 PLACEHOLDER-MOVED-TO-ENV
