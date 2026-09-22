-- ============================================================
-- 开发 / 演示用账号（**不要在生产库执行**）
-- ============================================================
--
-- 与 Flyway 迁移脚本的分工：
--   db/migration —— 生产也需要的基础数据（建表 + 租户 / 角色 / 权限点 / 楼栋 / 类别 / 报修码），
--                   任何新建的库都要有，所以由应用启动时的 Flyway 自动执行
--   dev-seed.sql —— **只有本地开发和演示才需要**的账号。它不挂在 initdb 上，
--                   所以生产库永远不会自动获得这些账号 —— 也就不需要"上线时记得删"。
--
-- 用法（本地，需先把 mysql 容器起起来）：
--   docker exec -i repair-mysql mysql -uroot -p"$MYSQL_ROOT_PASSWORD" \
--       --default-character-set=utf8mb4 < docs/dev-seed.sql
--   Windows PowerShell：
--     Get-Content docs\dev-seed.sql -Raw | docker exec -i repair-mysql mysql -uroot -p"$env:MYSQL_PASSWORD" --default-character-set=utf8mb4
--
-- 三个账号覆盖三种角色，**口令都是 `Repair@2026`**：
--
--   | 登录名   | 角色     | 能做什么                         |
--   | admin    | 后勤管理 | 派单、统计看板、AI 问数           |
--   | worker01 | 维修工   | 接单、到场打卡、完工上报（负责 1、2 号楼） |
--   | 20260001 | 学生     | 提交报修、查看进度、验收评价       |
--
-- ⚠️ 口令以 BCrypt 哈希存储，明文见上方说明（仅演示用途）。生产环境不要执行本脚本。
-- ============================================================

USE `repair`;

-- 可重复执行：先清掉这三个账号及其关联
DELETE FROM `sys_user_role`    WHERE `user_id` IN (1, 2, 3);
DELETE FROM `worker_building`  WHERE `worker_id` = 2;
DELETE FROM `sys_user`         WHERE `id` IN (1, 2, 3);

INSERT INTO `sys_user` (`id`, `tenant_id`, `username`, `password`, `real_name`, `phone`, `user_type`, `status`) VALUES
    (1, 1, 'admin',    '$2a$10$xR./bZUlygn8gjx10szFQeInCZ55Cd5ZwHhSXJlR6dtWMzE9YD4KG', '后勤管理员', '13800000001', 3, 1),
    (2, 1, 'worker01', '$2a$10$xR./bZUlygn8gjx10szFQeInCZ55Cd5ZwHhSXJlR6dtWMzE9YD4KG', '维修工小李', '13800000002', 2, 1),
    (3, 1, '20260001', '$2a$10$xR./bZUlygn8gjx10szFQeInCZ55Cd5ZwHhSXJlR6dtWMzE9YD4KG', '学生小王',   '13800000003', 1, 1);

-- 角色关联（角色 ID 来自迁移脚本的种子：1 学生、2 维修工、3 后勤管理）
INSERT INTO `sys_user_role` (`id`, `user_id`, `role_id`) VALUES
    (1, 1, 3),
    (2, 2, 2),
    (3, 3, 1);

-- 维修工负责的楼栋 —— 数据权限的物理依据（ADR-002），M2 做数据权限拦截器时会用到
INSERT INTO `worker_building` (`id`, `tenant_id`, `worker_id`, `building_id`) VALUES
    (1, 1, 2, 1),
    (2, 1, 2, 2);
