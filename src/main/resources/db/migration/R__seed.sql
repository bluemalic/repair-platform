-- ============================================================
-- R__seed · 基础数据（可重复执行）
-- ============================================================
--
-- 这是 Flyway 的 **repeatable migration**：文件名以 R__ 开头，**脚本内容一变就会重跑**。
-- 所以里面每一句都必须幂等——否则改一次脚本，数据就多一份。
--
-- 【两种幂等策略，按数据的归属分】
--
--   1) 平台定义的数据（角色、权限点）：`ON DUPLICATE KEY UPDATE`
--      —— 它们归平台管，脚本说了算。改了名称/描述，重跑就生效。
--
--   2) 租户自己的数据（租户信息、楼栋、类别、报修码）：`INSERT IGNORE`
--      —— 只补"缺的那条"，**已有的行绝不覆盖**。学校可能改过楼栋名、停用过某个类别，
--         种子脚本没有资格把它们改回去。
--
-- 角色-权限关联单独处理（见文件末尾）：它是"平台定义的授予关系"，但又要能增能减，
-- 所以用「先删内置角色的授予，再按脚本重建」——这样加权限码只需改这里，不用手写 UPDATE。
--
-- 【关于语法】`INSERT ... AS new ON DUPLICATE KEY UPDATE col = new.col`
--   是 MySQL 8.0.19+ 的写法，替代已废弃的 VALUES() 函数（8.0.20 起 deprecated）。
--   用新写法是为了将来升级 MySQL 时不用回来改；容器固定 mysql:8.0，满足版本要求。
--
-- 【为什么用 `ON DUPLICATE KEY UPDATE` 而不是 `INSERT IGNORE`】
--   IGNORE 遇到冲突会把它降级成"警告"打在**服务器日志**里，Flyway 每条都转记一遍——
--   重跑一次种子就刷十几行 Duplicate entry，把真正的错误淹掉。
--   下面这种"冲突时把主键更新成它自己"的写法是等价语义（什么都不做）但**不产生任何警告**。
-- ============================================================

-- ---------- 租户（学校 / 校区）----------
-- 冲突时什么都不做：租户名称、联系方式是学校自己维护的，种子只负责"首次建出来"
--
-- `id = 0` 那一行是**平台自身**，不是学校：平台运营账号（sys_user.tenant_id = 0）靠它登录，
-- 因为登录接口是按 tenantCode 查这张表定位租户的。与 sys_role.tenant_id = 0 表示"平台内置角色"
-- 是同一条约定。它不出现在平台运营端的租户列表里（列表显式排除了 id = 0）。
INSERT INTO `tenant` (`id`, `name`, `code`, `contact`, `phone`) VALUES
    (0, '平台运营', 'platform', NULL, NULL),
    (1, '广东海洋大学', 'gdou', '后勤管理处', '0759-0000000')
AS new ON DUPLICATE KEY UPDATE `id` = new.`id`;

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
-- 新增权限码只改这里：加一行 → 脚本校验和变化 → 下次启动重跑 → 权限点入库。
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
    (22, 'tenant:manage',       '租户管理',     2)
AS new ON DUPLICATE KEY UPDATE
    `code` = new.`code`,
    `name` = new.`name`;

-- ---------- 楼栋 ----------
-- 冲突时什么都不做：学校会改楼栋名（甚至删掉重建），种子只保证"首次有这么几栋"
INSERT INTO `building` (`id`, `tenant_id`, `name`, `area`, `sort`) VALUES
    (1, 1, '1号楼', '东区', 1),
    (2, 1, '2号楼', '东区', 2),
    (3, 1, '3号楼', '西区', 3),
    (4, 1, '4号楼', '西区', 4),
    (5, 1, '图书馆', '中心区', 5)
AS new ON DUPLICATE KEY UPDATE `id` = new.`id`;

-- ---------- 报修类别 ----------
INSERT INTO `ticket_category` (`id`, `tenant_id`, `name`, `default_urgency`, `sort`) VALUES
    (1, 1, '水电',   2, 1),
    (2, 1, '家具',   1, 2),
    (3, 1, '网络',   2, 3),
    (4, 1, '门锁',   2, 4),
    (5, 1, '空调',   1, 5),
    (6, 1, '其他',   1, 6)
AS new ON DUPLICATE KEY UPDATE `id` = new.`id`;

-- ---------- 报修码 ----------
-- 演示用固定码，方便把扫码流程跑通；生产由管理端随机生成，避免被枚举出全部房间。
-- 同样冲突时不动作：学校可能已经删掉或重新生成过某个房间的码，别去动它。
INSERT INTO `repair_code` (`id`, `tenant_id`, `code`, `building_id`, `room`) VALUES
    (1, 1, '482913', 1, '1-101'),
    (2, 1, '751204', 2, '2-201'),
    (3, 1, '306718', 3, '3-412'),
    (4, 1, '925146', 4, '4-305'),
    (5, 1, '640372', 5, '301')
AS new ON DUPLICATE KEY UPDATE `id` = new.`id`;

-- ---------- 角色-权限授予 ----------
-- 先删掉内置角色的授予，再按下面的清单重建：
--   · 这样"给某个角色加一个权限"只需在清单里加一行，不必手写 UPDATE/INSERT 语句
--   · 脚本重跑的结果始终等于清单本身，不会越跑越乱（幂等的更强形式：不是"不重复"，而是"收敛到清单"）
--   · 只删 role_id in (1,2,3,4)：将来租户自建角色的授予不受影响
--
-- 注意这里用的是**两条普通语句**，不要写成存储过程：`DELIMITER` 是 mysql 命令行的指令，
-- Flyway 是把脚本当 SQL 直接执行、并不认识它，写了会直接报语法错误。
-- 两条语句在同一个迁移事务里执行，效果等价于原子操作。
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
        (30, 3, 19), (31, 3, 20), (32, 3, 21),
    -- 平台运营：**只有租户管理这一个权限**。它看不到任何学校的业务数据，
    -- 这一点靠两层保证：① 这里只授一个码；② PlatformScopeInterceptor 把平台账号关在 /api/platform/** 里
    (33, 4, 22);

