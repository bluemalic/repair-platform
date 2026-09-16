-- ============================================================
-- 高校后勤报修平台 · 数据库初始化脚本
-- ============================================================
--
-- 执行时机：MySQL 容器【首次】启动时自动执行（compose 把它挂到
--           /docker-entrypoint-initdb.d/）。数据卷已存在则会跳过。
--
-- 改完本文件后要重新初始化。
-- 注意：本项目的 MySQL 数据是 bind mount 到 ./data/mysql 的，docker compose down -v
--       删不掉它（-v 只删 Docker 管理的命名卷），必须手动删目录：
--
--   docker compose down && rm -rf data/mysql && docker compose up -d mysql
--   本地 Windows（PowerShell）等价写法：
--   docker compose down; Remove-Item -Recurse -Force data\mysql; docker compose up -d mysql
--
-- ⚠️ 初始化脚本报错【不会】让容器退出，只会在日志里打一行 ERROR，容器照样显示 healthy。
--    执行后必须确认表真的建出来了（show tables），不要只看健康状态。
--    排查命令：docker logs repair-mysql | grep -i error
--
-- 设计说明与索引理由见 docs/02-数据库设计.md —— 本文件与那份文档必须保持一致。
--
-- 注意：本脚本末尾有初始化数据（租户/角色/权限/类别/楼栋/报修码），
--       修改这里的表结构时，记得同步更新 docs/02-数据库设计.md。
--
-- 与 docs/dev-seed.sql 的分工：本脚本只放**生产也需要**的基础数据；演示账号放在
-- dev-seed.sql 里，它不挂在 initdb 上、需要时手动执行一次，所以生产库不会多出这些账号。
-- ============================================================

SET NAMES utf8mb4;

CREATE DATABASE IF NOT EXISTS `repair`
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;
USE `repair`;

-- ⚠️ 排序规则要写两处，这两个坑都属于"看着对、实际没生效"：
--   1) 库：compose 给 mysql 服务设了 MYSQL_DATABASE，容器初始化时会先按服务器默认排序规则
--      （MySQL 8 是 utf8mb4_0900_ai_ci）把库建好，于是上面那句 CREATE DATABASE IF NOT EXISTS
--      成了空操作。用下面这句 ALTER 纠正库的默认值（它决定以后新建表用什么）。
--   2) 表：建表时若只写 DEFAULT CHARSET = utf8mb4 而不写 COLLATE，MySQL 用的是
--      **该字符集的默认排序规则** utf8mb4_0900_ai_ci，而不是库的排序规则。
--      所以下面 14 张表都显式写了 COLLATE = utf8mb4_unicode_ci。
ALTER DATABASE `repair` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;

SET FOREIGN_KEY_CHECKS = 0;

-- ============================================================
-- 一、租户与权限
-- ============================================================

-- 租户（学校 / 校区）。做成 SaaS 的技术基础：所有业务表都带 tenant_id
DROP TABLE IF EXISTS `tenant`;
CREATE TABLE `tenant` (
    `id`          bigint      NOT NULL COMMENT '租户ID',
    `name`        varchar(64) NOT NULL COMMENT '租户名称，如「广东海洋大学」',
    `code`        varchar(32) NOT NULL COMMENT '租户编码，登录时用于定位租户',
    `contact`     varchar(32)  DEFAULT NULL COMMENT '联系人',
    `phone`       varchar(20)  DEFAULT NULL COMMENT '联系电话',
    `status`      tinyint     NOT NULL DEFAULT 1 COMMENT '状态 1启用 0停用',
    `deleted`     tinyint     NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否 1是',
    `create_time` datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_code` (`code`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='租户（学校/校区）';

-- 用户表：三种角色共用一张表，靠 user_type 区分
-- ⚠️ 为什么不用三张表：登录逻辑、密码校验、token 管理完全一致，拆表会导致重复代码
DROP TABLE IF EXISTS `sys_user`;
CREATE TABLE `sys_user` (
    `id`          bigint      NOT NULL COMMENT '用户ID',
    `tenant_id`   bigint      NOT NULL COMMENT '租户ID',
    `username`    varchar(32) NOT NULL COMMENT '登录名（学生用学号、维修工用工号）',
    `password`    varchar(100) NOT NULL COMMENT 'BCrypt 加密后的密码，绝不存明文',
    `real_name`   varchar(32)  DEFAULT NULL COMMENT '姓名',
    `phone`       varchar(20)  DEFAULT NULL COMMENT '手机号',
    `user_type`   tinyint     NOT NULL COMMENT '用户类型 1学生 2维修工 3后勤管理',
    `status`      tinyint     NOT NULL DEFAULT 1 COMMENT '状态 1启用 0停用',
    `deleted`     tinyint     NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否 1是',
    `create_time` datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant_username` (`tenant_id`, `username`),
    KEY `idx_tenant_type` (`tenant_id`, `user_type`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='用户（学生/维修工/后勤管理）';

-- 角色。tenant_id = 0 表示平台内置角色（所有租户共用）
DROP TABLE IF EXISTS `sys_role`;
CREATE TABLE `sys_role` (
    `id`          bigint      NOT NULL COMMENT '角色ID',
    `tenant_id`   bigint      NOT NULL DEFAULT 0 COMMENT '租户ID，0=平台内置角色',
    `code`        varchar(32) NOT NULL COMMENT '角色编码，如 STUDENT / WORKER / ADMIN',
    `name`        varchar(32) NOT NULL COMMENT '角色名称',
    `description` varchar(128) DEFAULT NULL COMMENT '描述',
    `status`      tinyint     NOT NULL DEFAULT 1 COMMENT '状态 1启用 0停用',
    `deleted`     tinyint     NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否 1是',
    `create_time` datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant_code` (`tenant_id`, `code`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='角色';

-- 权限点。权限是全局的（不区分租户），分菜单/按钮两级
DROP TABLE IF EXISTS `sys_permission`;
CREATE TABLE `sys_permission` (
    `id`          bigint      NOT NULL COMMENT '权限ID',
    `code`        varchar(64) NOT NULL COMMENT '权限编码，如 ticket:dispatch',
    `name`        varchar(32) NOT NULL COMMENT '权限名称',
    `type`        tinyint     NOT NULL DEFAULT 2 COMMENT '类型 1菜单 2按钮/接口',
    `parent_id`   bigint      NOT NULL DEFAULT 0 COMMENT '父权限ID，0为顶层',
    `sort`        int         NOT NULL DEFAULT 0 COMMENT '排序',
    `status`      tinyint     NOT NULL DEFAULT 1 COMMENT '状态 1启用 0停用',
    `create_time` datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_code` (`code`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='权限点';

DROP TABLE IF EXISTS `sys_user_role`;
CREATE TABLE `sys_user_role` (
    `id`      bigint NOT NULL COMMENT '主键',
    `user_id` bigint NOT NULL COMMENT '用户ID',
    `role_id` bigint NOT NULL COMMENT '角色ID',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_role` (`user_id`, `role_id`),
    KEY `idx_role` (`role_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='用户-角色关联';

DROP TABLE IF EXISTS `sys_role_permission`;
CREATE TABLE `sys_role_permission` (
    `id`            bigint NOT NULL COMMENT '主键',
    `role_id`       bigint NOT NULL COMMENT '角色ID',
    `permission_id` bigint NOT NULL COMMENT '权限ID',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_role_permission` (`role_id`, `permission_id`),
    KEY `idx_permission` (`permission_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='角色-权限关联';

-- ============================================================
-- 二、基础数据（楼栋、维修工负责范围、报修类别）
-- ============================================================

DROP TABLE IF EXISTS `building`;
CREATE TABLE `building` (
    `id`          bigint      NOT NULL COMMENT '楼栋ID',
    `tenant_id`   bigint      NOT NULL COMMENT '租户ID',
    `name`        varchar(32) NOT NULL COMMENT '楼栋名称，如「3号楼」',
    `area`        varchar(32)  DEFAULT NULL COMMENT '所属区域，如「东区」',
    `sort`        int         NOT NULL DEFAULT 0 COMMENT '排序',
    `status`      tinyint     NOT NULL DEFAULT 1 COMMENT '状态 1启用 0停用',
    `deleted`     tinyint     NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否 1是',
    `create_time` datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_tenant` (`tenant_id`, `status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='楼栋';

-- ⭐ 维修工负责的楼栋 —— 这就是「数据权限」的物理落点
-- 常见疑问：维修工的数据隔离怎么实现？答案：靠这张表确定可见楼栋，
--           再由 MyBatis 拦截器自动把 building_id 条件注入到 SQL 里。
DROP TABLE IF EXISTS `worker_building`;
CREATE TABLE `worker_building` (
    `id`          bigint   NOT NULL COMMENT '主键',
    `tenant_id`   bigint   NOT NULL COMMENT '租户ID',
    `worker_id`   bigint   NOT NULL COMMENT '维修工用户ID',
    `building_id` bigint   NOT NULL COMMENT '楼栋ID',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_worker_building` (`worker_id`, `building_id`),
    KEY `idx_building` (`building_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='维修工负责的楼栋（数据权限依据）';

DROP TABLE IF EXISTS `ticket_category`;
CREATE TABLE `ticket_category` (
    `id`              bigint      NOT NULL COMMENT '类别ID',
    `tenant_id`       bigint      NOT NULL COMMENT '租户ID',
    `name`            varchar(32) NOT NULL COMMENT '类别名称，如「水电」',
    `default_urgency` tinyint     NOT NULL DEFAULT 1 COMMENT '默认紧急度 1普通 2紧急 3特急',
    `sort`            int         NOT NULL DEFAULT 0 COMMENT '排序',
    `status`          tinyint     NOT NULL DEFAULT 1 COMMENT '状态 1启用 0停用',
    `deleted`         tinyint     NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否 1是',
    `create_time`     datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`     datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_tenant` (`tenant_id`, `status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='报修类别';

-- ⭐ 报修码：贴在房间门口的「位置码」，标识楼栋 + 房间（**不是工单码**，工单号才是某次维修的标识）。
-- 学生扫码 / 手输 → 自动带出楼栋房间去提交报修；维修工到场扫同一个码 → 校验位置后打卡。
-- 两端共用 GET /api/tickets/by-code/{code} —— 这是「跨端抽象」的关键。
-- 码由管理端按需生成（生产环境随机生成，避免顺序码被枚举出全部房间）；二维码内容就是 code 本身，
-- 前端用 qrcode 库渲染后打印张贴。打印是学校的一次性运维动作，不影响应用开发。
DROP TABLE IF EXISTS `repair_code`;
CREATE TABLE `repair_code` (
    `id`          bigint      NOT NULL COMMENT '主键',
    `tenant_id`   bigint      NOT NULL COMMENT '租户ID',
    `code`        varchar(16) NOT NULL COMMENT '报修码，租户内唯一，扫码/手输用',
    `building_id` bigint      NOT NULL COMMENT '楼栋ID',
    `room`        varchar(32) NOT NULL COMMENT '房间号，如 3-412',
    `status`      tinyint     NOT NULL DEFAULT 1 COMMENT '状态 1启用 0停用',
    `deleted`     tinyint     NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否 1是',
    `create_time` datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_code` (`tenant_id`, `code`),
    -- 「一房一码」由应用层保证：不给 (building_id, room) 建唯一索引，
    -- 是因为逻辑删除后同一房间需要能重新生成码，唯一索引会和 deleted 冲突。
    KEY `idx_room` (`tenant_id`, `building_id`, `room`),
    KEY `idx_building` (`building_id`, `status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='报修码（房间位置码）';

-- ============================================================
-- 三、工单（项目核心）
-- ============================================================

DROP TABLE IF EXISTS `ticket`;
CREATE TABLE `ticket` (
    `id`               bigint       NOT NULL COMMENT '工单ID（雪花）',
    `tenant_id`        bigint       NOT NULL COMMENT '租户ID',
    `ticket_no`        varchar(32)  NOT NULL COMMENT '工单号，展示用，如 WX20260910001',
    `student_id`       bigint       NOT NULL COMMENT '报修学生ID',
    `worker_id`        bigint       DEFAULT NULL COMMENT '维修工ID，未派单为 NULL',
    `building_id`      bigint       NOT NULL COMMENT '楼栋ID',
    `room`             varchar(32)  NOT NULL COMMENT '房间号，如 3-412',
    `category_id`      bigint       NOT NULL COMMENT '报修类别ID',
    `description`      varchar(500) DEFAULT NULL COMMENT '问题描述',
    `images`           json         DEFAULT NULL COMMENT '现场图片URL数组',
    `urgency`          tinyint      NOT NULL DEFAULT 1 COMMENT '紧急度 1普通 2紧急 3特急',
    `status`           tinyint      NOT NULL DEFAULT 10 COMMENT '状态 10待派单 20待接单 30处理中 40待验收 50已完成 60已关闭 70已撤单 80已驳回',
    `dispatch_type`    tinyint      DEFAULT NULL COMMENT '派单方式 1手动 2自动',
    `reject_reason`    varchar(255) DEFAULT NULL COMMENT '驳回理由',
    `result_desc`      varchar(500) DEFAULT NULL COMMENT '维修结果说明',
    `result_images`    json         DEFAULT NULL COMMENT '维修后照片URL数组',
    `submit_time`      datetime     NOT NULL COMMENT '提交时间',
    `dispatch_time`    datetime     DEFAULT NULL COMMENT '派单时间',
    `accept_time`      datetime     DEFAULT NULL COMMENT '接单时间',
    `arrive_time`      datetime     DEFAULT NULL COMMENT '到场时间（维修工扫报修码打卡）',
    `finish_time`      datetime     DEFAULT NULL COMMENT '完工时间',
    `close_time`       datetime     DEFAULT NULL COMMENT '关闭时间',
    `arrive_minutes`   int          DEFAULT NULL COMMENT '响应时长(分钟) = 到场时间 - 派单时间，冗余字段',
    `handle_minutes`   int          DEFAULT NULL COMMENT '处理时长(分钟) = 完工时间 - 到场时间，冗余字段',
    `deleted`          tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否 1是',
    `create_time`      datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`      datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_ticket_no` (`ticket_no`),
    KEY `idx_tenant_status` (`tenant_id`, `status`, `submit_time`),
    KEY `idx_student` (`student_id`, `submit_time`),
    KEY `idx_worker_status` (`worker_id`, `status`, `submit_time`),
    KEY `idx_building_time` (`building_id`, `submit_time`),
    -- 超时兜底扫描是系统上下文（无租户条件），用不上带 tenant_id 前缀的索引，所以单开一条
    KEY `idx_status_dispatch`(`status`, `dispatch_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='维修工单主表';

-- 工单流转日志：每次状态变更写一条，用于「流转可追溯」
DROP TABLE IF EXISTS `ticket_log`;
CREATE TABLE `ticket_log` (
    `id`          bigint       NOT NULL COMMENT '主键',
    `tenant_id`   bigint       NOT NULL COMMENT '租户ID',
    `ticket_id`   bigint       NOT NULL COMMENT '工单ID',
    `from_status` tinyint      DEFAULT NULL COMMENT '变更前状态，首次创建为 NULL',
    `to_status`   tinyint      NOT NULL COMMENT '变更后状态',
    `action`      varchar(32)  NOT NULL COMMENT '动作，如 DISPATCH / ACCEPT / ARRIVE / FINISH',
    `operator_id` bigint       NOT NULL COMMENT '操作人ID',
    `remark`      varchar(255) DEFAULT NULL COMMENT '备注',
    `create_time` datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_ticket` (`ticket_id`, `create_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='工单流转日志';

DROP TABLE IF EXISTS `ticket_evaluation`;
CREATE TABLE `ticket_evaluation` (
    `id`            bigint       NOT NULL COMMENT '主键',
    `tenant_id`     bigint       NOT NULL COMMENT '租户ID',
    `ticket_id`     bigint       NOT NULL COMMENT '工单ID',
    `student_id`    bigint       NOT NULL COMMENT '评价人（学生）ID',
    `score`         tinyint      NOT NULL COMMENT '评分 1-5',
    `content`       varchar(500) DEFAULT NULL COMMENT '评价内容',
    `create_time`   datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    -- ⭐ 唯一索引：一个工单只能评价一次，这是「评价幂等」的兜底
    UNIQUE KEY `uk_ticket` (`ticket_id`),
    KEY `idx_student` (`student_id`),
    -- 兜底扫描按 create_time 找"评价超期且工单仍为 50"的记录，没这条会全表扫
    KEY `idx_create_time` (`create_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='工单验收评价';

-- ============================================================
-- 四、站内通知（跨端通用的通知方案，不用短信/邮件/订阅消息）
-- ============================================================

DROP TABLE IF EXISTS `notification`;
CREATE TABLE `notification` (
    `id`          bigint       NOT NULL COMMENT '主键',
    `tenant_id`   bigint       NOT NULL COMMENT '租户ID',
    `receiver_id` bigint       NOT NULL COMMENT '接收人用户ID',
    `type`        varchar(32)  NOT NULL COMMENT '通知类型，如 TICKET_DISPATCHED / TICKET_ACCEPTED',
    `title`       varchar(64)  NOT NULL COMMENT '标题',
    `content`     varchar(255) DEFAULT NULL COMMENT '内容',
    `ticket_id`   bigint       DEFAULT NULL COMMENT '关联工单ID，可空',
    `is_read`     tinyint      NOT NULL DEFAULT 0 COMMENT '是否已读 0未读 1已读',
    `create_time` datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    -- 前端最高频的查询是「我的未读数」，这个索引专门服务它
    KEY `idx_receiver_read` (`receiver_id`, `is_read`, `create_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='站内通知';

-- ============================================================
-- 五、初始化数据
-- 说明：这几张表是「系统跑起来就需要」的基础数据，所以直接初始化。
--       用户（sys_user）不在脚本里初始化 —— 密码要经过 BCrypt 加密，
--       请在第 5 阶段实现 PasswordEncoder 后，用测试代码生成密码再插入。
-- ============================================================

-- 租户
INSERT INTO `tenant` (`id`, `name`, `code`, `contact`, `phone`) VALUES
    (1, '广东海洋大学', 'gdou', '后勤管理处', '0759-0000000');

-- 角色（tenant_id = 0 表示平台内置）
INSERT INTO `sys_role` (`id`, `tenant_id`, `code`, `name`, `description`) VALUES
    (1, 0, 'STUDENT', '学生', '提交报修、查看进度、验收评价'),
    (2, 0, 'WORKER', '维修工', '接单、到场打卡、上报维修结果'),
    (3, 0, 'ADMIN', '后勤管理', '派单调度、统计看板、AI 问数');

-- 权限点
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
    (20, 'repaircode:manage',   '报修码管理',   2);

-- 角色-权限
INSERT INTO `sys_role_permission` (`id`, `role_id`, `permission_id`) VALUES
    -- 学生
    (1, 1, 1), (2, 1, 2), (3, 1, 3), (4, 1, 4), (5, 1, 19),
    -- 维修工
    (6, 2, 5), (7, 2, 6), (8, 2, 7), (9, 2, 8), (10, 2, 9), (11, 2, 19),
    -- 后勤管理：全部权限
    (12, 3, 1), (13, 3, 2), (14, 3, 3), (15, 3, 4), (16, 3, 5), (17, 3, 6),
    (18, 3, 7), (19, 3, 8), (20, 3, 9), (21, 3, 10), (22, 3, 11), (23, 3, 12),
    (24, 3, 13), (25, 3, 14), (26, 3, 15), (27, 3, 16), (28, 3, 17), (29, 3, 18), (30, 3, 19),
    (31, 3, 20);

-- 楼栋
INSERT INTO `building` (`id`, `tenant_id`, `name`, `area`, `sort`) VALUES
    (1, 1, '1号楼', '东区', 1),
    (2, 1, '2号楼', '东区', 2),
    (3, 1, '3号楼', '西区', 3),
    (4, 1, '4号楼', '西区', 4),
    (5, 1, '图书馆', '中心区', 5);

-- 报修类别
INSERT INTO `ticket_category` (`id`, `tenant_id`, `name`, `default_urgency`, `sort`) VALUES
    (1, 1, '水电',   2, 1),
    (2, 1, '家具',   1, 2),
    (3, 1, '网络',   2, 3),
    (4, 1, '门锁',   2, 4),
    (5, 1, '空调',   1, 5),
    (6, 1, '其他',   1, 6);

-- 报修码（演示用固定码，方便本地把扫码流程跑通；生产由管理端随机生成，避免被枚举出全部房间）
INSERT INTO `repair_code` (`id`, `tenant_id`, `code`, `building_id`, `room`) VALUES
    (1, 1, '482913', 1, '1-101'),
    (2, 1, '751204', 2, '2-201'),
    (3, 1, '306718', 3, '3-412'),
    (4, 1, '925146', 4, '4-305'),
    (5, 1, '640372', 5, '301');

SET FOREIGN_KEY_CHECKS = 1;
