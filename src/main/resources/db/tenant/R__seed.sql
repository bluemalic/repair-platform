-- ============================================================
-- R__seed · 平台内置的角色与权限（可重复执行）
-- ============================================================
--
-- 租户库里的这份种子只放**平台定义的数据**：角色、权限点、角色-权限授予。
-- 学校自己的数据（楼栋、类别、报修码、知识库条目）**不在**这里——那是学校在后台建的。
--
-- 【为什么要放进每个租户库，而不是"只查平台库"】
--   鉴权是每个请求的第一件事（`@SaCheckPermission` → `StpInterface` → 角色/权限查询），
--   而它是**在租户请求的上下文里**跑的。如果这些行只在平台库，那么每个请求都要跨库查一次，
--   且"账号 + 角色授予"这种同事务写入会跨库。放进租户库后，鉴权与业务在一个库、一个事务里。
--   同一条行在每个库里都带 `tenant_id = 0`（= 平台内置），语义没有变，变的只是它物理上在哪。
--
-- 【与 db/migration/R__seed.sql 的关系】
--   那个文件同时管平台库的角色/权限**与租户 1 的演示数据**；这个文件只管角色/权限（平台定义的部分）。
--   两份内容必须一致——`TenantSchemaParityTest` 会逐行比对平台库与租户库里的
--   `sys_role` / `sys_permission` / `sys_role_permission`，改了一处忘另一处的话 CI 直接红。
--
-- 【什么时候重跑】
--   · 新开学校：建库后跑一遍（`TenantDatabaseProvisioner`）；
--   · 每次应用启动：对每个已有租户库再跑一遍 Flyway（`TenantSchemaBootstrap`）——
--     可重复迁移在**内容变化时**重跑，所以"平台新增一个权限码"会在下次启动时同步到所有学校。
--
-- 幂等策略与平台库那份相同：角色/权限用 `ON DUPLICATE KEY UPDATE`（平台说了算，改名要生效），
-- 角色-权限授予用「先删内置角色的授予，再按清单重建」（加一行清单即可，不必手写 UPDATE）。
-- 用 `AS new ON DUPLICATE KEY UPDATE` 而不是 VALUES()：后者自 8.0.20 起 deprecated。
-- ============================================================

-- ---------- 角色（tenant_id = 0 表示平台内置）----------
INSERT INTO `sys_role` (`id`, `tenant_id`, `code`, `name`, `description`) VALUES
    (1, 0, 'STUDENT', '学生', '提交报修、查看进度、验收评价'),
    (2, 0, 'WORKER', '维修工', '接单、到场打卡、上报维修结果'),
    (3, 0, 'ADMIN', '后勤管理', '派单调度、统计看板、AI 问数'),
    (4, 0, 'PLATFORM', '平台运营', '开通 / 停用租户、维护租户的后勤管理员')
AS new ON DUPLICATE KEY UPDATE
    `name` = new.`name`,
    `description` = new.`description`;

-- ---------- 权限点 ----------
INSERT INTO `sys_permission` (`id`, `code`, `name`, `type`) VALUES
    (1,  'ticket:create',       '提交报修',     2),
    (2,  'ticket:list:self',    '查看我的工单', 2),
    (3,  'ticket:cancel',       '撤销工单',     2),
    (4,  'ticket:evaluate',     '验收评价',     2),
    (5,  'ticket:list:assigned','查看我的派单', 2),
    (6,  'ticket:accept',       '接单',         2),
    (7,  'ticket:reject',       '驳回工单',     2),
    (8,  'ticket:arrive',       '扫报到场',     2),
    (9,  'ticket:finish',       '完工上报',     2),
    (10, 'ticket:list:all',     '查看全部工单', 2),
    (11, 'ticket:dispatch',     '派单',         2),
    (12, 'ticket:transfer',     '转派',         2),
    (13, 'ticket:close',        '关闭工单',     2),
    (14, 'building:manage',     '楼栋管理',     2),
    (15, 'category:manage',     '报修类别管理', 2),
    (16, 'worker:manage',       '维修工管理',   2),
    (17, 'statistics:view',     '查看统计看板', 2),
    (18, 'ai:query',            'AI 数据问数',  2),
    (19, 'notification:read',   '查看通知',     2),
    (20, 'repaircode:manage',   '报修码管理',   2),
    (21, 'student:manage',      '学生账号管理', 2),
    (22, 'tenant:manage',       '租户管理',     2),
    (23, 'audit:list',          '查看操作日志', 2),
    (24, 'knowledge:manage',    '维修知识库管理', 2)
AS new ON DUPLICATE KEY UPDATE
    `code` = new.`code`,
    `name` = new.`name`;

-- ---------- 角色-权限授予 ----------
-- 先删掉内置角色的授予，再按下面的清单重建（与平台库那份同一个手法，只删 role_id in (1,2,3,4)，
-- 学校自建角色的授予不受影响）。两条普通语句，不要写成存储过程：DELIMITER 是 mysql 命令行的
-- 指令，Flyway 不认识它。
DELETE FROM `sys_role_permission` WHERE `role_id` IN (1, 2, 3, 4);

INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES
    -- 学生
    (1, 1, 1), (2, 1, 2), (3, 1, 3), (4, 1, 4), (5, 1, 19),
    -- 维修工
    (6, 2, 5), (7, 2, 6), (8, 2, 7), (9, 2, 8), (10, 2, 9), (11, 2, 19),
    -- 后勤管理：全部权限
    (12, 3, 1), (13, 3, 2), (14, 3, 3), (15, 3, 4), (16, 3, 5), (17, 3, 6),
    (18, 3, 7), (19, 3, 8), (20, 3, 9), (21, 3, 10), (22, 3, 11), (23, 3, 12),
        (24, 3, 13), (25, 3, 14), (26, 3, 15), (27, 3, 16), (28, 3, 17), (29, 3, 18),
        (30, 3, 19), (31, 3, 20), (32, 3, 21), (34, 3, 23), (35, 3, 24),
    -- 平台运营：**只有租户管理这一个权限**（平台账号在租户库里基本用不到，这里保持与平台库一致）
    (33, 4, 22);
