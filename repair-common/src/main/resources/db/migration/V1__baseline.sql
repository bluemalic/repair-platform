-- ============================================================
-- V1 · 基线建表（14 张表）
-- ============================================================
--
-- 这份脚本是**表结构的执行真相**。以前这件事由 docs/schema.sql 承担，但它只在
-- MySQL 容器首次初始化时执行一次——库一旦有数据，改表就只剩「手工进容器 ALTER」这条路，
-- 没有版本、没有记录、换台机器要重来一遍。现在交给 Flyway：应用启动时按版本号顺序
-- 执行没跑过的脚本，并在 flyway_schema_history 里留下记录。
--
-- ⚠️ **迁移脚本里不允许出现 DROP TABLE / TRUNCATE**。
--    基线的含义是"把一个空库建成这个样子"，不是"把库清空重来"。
--    需要清库重建时，那是一次人工决策，走 docs/05 的步骤，不该藏在启动流程里。
--
-- 关于这些表的设计理由（为什么这样分表、索引为什么这么建、字段为什么这么设计），
-- 写在 docs/02-数据库设计.md 里；改表结构时两处一起改。
--
-- 【没有 CREATE DATABASE / USE】：
--   Flyway 连的是 JDBC URL 里的库（本地与 CI 都由 MySQL 的 MYSQL_DATABASE 建好），
--   迁移脚本不该也不能假设库名。
--
-- 【没有 ALTER DATABASE ... COLLATE】：以前 schema.sql 里有这一句，是为了纠正
--   MySQL 8 默认的 utf8mb4_0900_ai_ci（容器按服务器默认排序规则建库）。
--   它只影响"建表时没写 COLLATE"的情况，而下面每张表都显式写了 COLLATE = utf8mb4_unicode_ci，
--   所以这一句不再需要。查询时的字符串比较走列上的排序规则，也不是库的默认值。
-- ============================================================

SET FOREIGN_KEY_CHECKS = 0;

-- ============================================================
-- 一、租户与权限
-- ============================================================

-- 租户（学校 / 校区）。做成 SaaS 的技术基础：所有业务表都带 tenant_id
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

CREATE TABLE `sys_user_role` (
    `id`      bigint NOT NULL COMMENT '主键',
    `user_id` bigint NOT NULL COMMENT '用户ID',
    `role_id` bigint NOT NULL COMMENT '角色ID',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_role` (`user_id`, `role_id`),
    KEY `idx_role` (`role_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='用户-角色关联';

CREATE TABLE `sys_role_permission` (
    `id`            bigint NOT NULL COMMENT '主键',
    `role_id`       bigint NOT NULL COMMENT '角色ID',
    `permission_id` bigint NOT NULL COMMENT '权限ID',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_role_permission` (`role_id`, `permission_id`),
    KEY `idx_permission` (`permission_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='角色-权限关联';

-- ============================================================
-- 二、基础数据（楼栋、维修工负责范围、报修类别、报修码）
-- ============================================================

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

SET FOREIGN_KEY_CHECKS = 1;
