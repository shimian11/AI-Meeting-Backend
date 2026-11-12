-- ============================================================
-- migration-01-mail-login.sql · 登录改造增量
-- 阶段：Sa-Token 邮箱登录（现在）
-- 前置：schema.sql 已执行（开发库从零建表时，本文件的 ALTER 项已包含在 schema 中，
--       此时只需执行「清库」段；本文件按"老库增量"场景写，全量场景见下方说明）
-- ============================================================

-- ── 老库增量场景（库里有旧数据）──────────────────────────────

-- ① mail 唯一索引（schema.sql 已含 uk_mail 的建表版，此处为 ALTER 版）
ALTER TABLE t_user ADD UNIQUE KEY uk_mail (mail);

-- ② 清库：明文密码种子 + 非邮箱 username 无迁移价值
--    admin_permission 内 3 个历史管理员已确认无代码依赖，一并清除
SET FOREIGN_KEY_CHECKS = 0;
TRUNCATE TABLE t_user;
TRUNCATE TABLE admin_permission;
TRUNCATE TABLE agent_file_asset;
TRUNCATE TABLE interview_record;
SET FOREIGN_KEY_CHECKS = 1;

-- ③ t_user.sql 种子【废弃不执行】（明文密码 + 非邮箱 username）
--    首个账号通过前端注册产生

-- ── 全量场景（新库，schema.sql 刚执行完）────────────────────
-- 只需执行：TRUNCATE 4 行（或跳过——schema 建完本来就是空的），
-- 然后重启 Java + Redis FLUSHDB

-- ============================================================
-- 验证
-- ============================================================
-- SHOW INDEX FROM t_user;        应出现 uk_mail
-- SELECT COUNT(*) FROM t_user;   应为 0

-- ============================================================
-- 配套动作（非 SQL，必做）
-- ============================================================
-- 1. 重启 Java：布隆过滤器重建（旧用户名残留会误判「已存在」）
-- 2. Redis FLUSHDB：清登录态（VM 上 docker exec -it banma-redis redis-cli FLUSHDB）
