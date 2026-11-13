-- ============================================================
-- migration-02-drop-legacy-agent-credentials.sql · 清理 agent_properties 遗留凭证列
-- 阶段：6A'（原 6A 已取消，见 ADR-017）
-- 背景：api_secret / api_key / api_flow_id 是讯飞星辰遗留的死字段——
--       只被 update 接口写入、无任何调用逻辑读取，且 apiKey 曾随查询接口明文返回；
--       agent_type 是双路由开关，星辰通道已整体下线，开关两侧一侧能跑一侧必炸，无意义。
-- 前置：确认表为空或数据可弃（本项目此时 0 行）
-- ============================================================

ALTER TABLE agent_properties
    DROP COLUMN api_secret,
    DROP COLUMN api_key,
    DROP COLUMN api_flow_id;

-- agent_type 若已存在（曾执行过 migration-02）则一并删除
-- MySQL 8.0 不支持 DROP COLUMN IF EXISTS，需先判断；此处按"直接删"给出，
-- 若列不存在会报 1091，可忽略后重跑剩余语句。
ALTER TABLE agent_properties
    DROP COLUMN agent_type;

-- ============================================================
-- 验证
-- ============================================================
-- DESC agent_properties;
--   期望只剩：id / agent_name / create_time / update_time / del_flag
-- SELECT * FROM agent_properties;
--   期望看不到任何 *key* / *secret* / *flow* 列
