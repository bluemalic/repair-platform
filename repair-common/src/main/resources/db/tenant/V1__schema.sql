-- ============================================================
-- T1 · 租户库结构（每个学校一个 database，ADR-021）
-- ============================================================
--
-- 这份脚本建的是**一个学校自己的库**（`repair_<学校编码>`）。它由开学校那一步创建：
-- 建库 → 跑这份脚本 → 建租户账号（见 `TenantDatabaseProvisioner`）。
--
-- 【和 db/migration 是什么关系】
--   `db/migration` 是**平台库**（`repair`）的结构：控制面（`tenant`）+ 过渡期里存量租户的数据；
--   这里是**租户库**的结构。两个位置是两份独立的 Flyway 历史（每个库有自己的
--   `flyway_schema_history`），版本号也各排各的：平台库现在到 V12，租户库从这里 T1 开始
--   （前缀用 T 是为了让人一眼看出"这不是平台库的脚本"）。
--
--   ⚠️ **改表结构要改两处**：租户侧的表（下面 17 张）既存在于平台库（存量租户还没搬走），
--      也存在于每个租户库。所以一次结构变更 = `db/migration/V{n}__xxx.sql`（平台库）
--      + `db/tenant/T{m}__xxx.sql`（租户库）。两份漂开是**静默**的
--      （一个库有列、另一个没有 → 只有跑到那条 SQL 才报错），所以有
--      `TenantSchemaParityTest` 盯着：它把两份脚本各跑进一个临时库，逐列逐索引比对，
--      再加一份平台种子数据（角色/权限/授权）的逐行比对。漏改一处，CI 直接红。
--
-- 【哪些表**不在**这里】
--   · `tenant`（学校名录）：只在平台库。登录时还不知道该去哪个租户库，必须先按学校编码
--     在平台库定位——所以它的唯一所在地是平台库（ADR-021 决定 2）。租户库里没有这张表，
--     租户上下文里去查它会**直接报错**（表不存在），这正是想要的 fail-closed。
--
-- 【这里没有租户 1 的演示数据】
--   楼栋 / 类别 / 报修码 / 知识库条目这些是**学校自己的数据**：新学校建号后由管理员在后台建
--   （或后续做"开通时选模板"）。种子脚本只放**平台定义的数据**（角色、权限点、角色-权限授予），
--   见同目录的 `R__seed.sql`。
--
-- 【为什么租户库里也有 sys_user / sys_role / sys_permission】
--   一开始的设计是"账号与角色都留在平台库"，落地时改了（ADR-021 的「落地时的修正」）：
--   统计报表要 `JOIN sys_user` 取师傅姓名、通知要按角色反查收件人、账号列表要连
--   `worker_skill`——这些**都在租户自己的事务里**。真按控制面/数据面切，它们全会变成跨库查询
--   （要么带库名前缀、要么两段查再在内存里拼），而且"账号 + 审计"这种同事务写入会**悄悄失去原子性**。
--   改成"租户库装完整的一套"之后：租户的一次业务 = 它自己库里的一个事务，边界干净。
--   角色与权限是**平台内置行**（`tenant_id = 0`），在平台库与每个租户库里各有一份同样的副本，
--   由 `R__seed.sql` 保持同步（应用启动时会为每个租户库跑一遍这个可重复迁移）。
--
-- 【没有 CREATE DATABASE / USE】：库由 provisioning 建好，Flyway 连的是 JDBC URL 里的库。
-- 【没有 DROP TABLE / TRUNCATE】：脚本只负责"把一个空库建成这个样子"（AGENTS §6）。
-- ============================================================

SET FOREIGN_KEY_CHECKS = 0;

-- ============================================================
-- 一、账号与权限（平台内置角色 + 学校自己的账号）
-- ============================================================

-- 用户表：学校里的三种角色共用一张表，靠 user_type 区分
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
    `must_change_password` tinyint NOT NULL DEFAULT 0
        COMMENT '是否需强制改密 0否 1是（管理员建的账号为 1，改密成功后置 0）',
    `deleted`     tinyint     NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否 1是',
    `create_time` datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant_username` (`tenant_id`, `username`),
    KEY `idx_tenant_type` (`tenant_id`, `user_type`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='用户（学生/维修工/后勤管理）';

-- 角色。tenant_id = 0 表示平台内置角色（所有学校共用同一套定义）
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
-- 二、基础数据（楼栋、维修工负责范围与技能、报修类别、报修码）
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

-- 维修工擅长类别（派单评分的技能维度，ADR-019）
-- 形态照 worker_building：带 tenant_id、没有 update_time / deleted——取消技能就是删掉这行
CREATE TABLE `worker_skill` (
    `id`          bigint   NOT NULL COMMENT '主键（雪花）',
    `tenant_id`   bigint   NOT NULL COMMENT '租户ID',
    `worker_id`   bigint   NOT NULL COMMENT '维修工用户ID',
    `category_id` bigint   NOT NULL COMMENT '擅长类别（ticket_category.id）',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '设置时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_worker_category` (`worker_id`, `category_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='维修工擅长类别（派单评分的技能维度，ADR-019）';

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

-- ⭐ 报修码：贴在房间门口的「位置码」，标识楼栋 + 房间（**不是工单码**）
-- location_type = 1 宿舍房间（room 是房间号）/ 2 公共区域（room 存区域名，如 1F-大厅）
CREATE TABLE `repair_code` (
    `id`          bigint      NOT NULL COMMENT '主键',
    `tenant_id`   bigint      NOT NULL COMMENT '租户ID',
    `code`        varchar(16) NOT NULL COMMENT '报修码，租户内唯一，扫码/手输用',
    `building_id` bigint      NOT NULL COMMENT '楼栋ID',
    `room`        varchar(32) NOT NULL COMMENT '房间号，如 3-412',
    `location_type` tinyint   NOT NULL DEFAULT 1 COMMENT '1 宿舍房间 / 2 公共区域',
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
    `parent_ticket_id` bigint       DEFAULT NULL COMMENT '拆单来源工单ID，非拆单产生的为 NULL',
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
    KEY `idx_status_dispatch`(`status`, `dispatch_time`),
    -- 「本租户 + 未删除 + 按提交时间倒序」：管理端默认列表、看板的时间区间聚合都走它（V4，docs/08）
    KEY `idx_tenant_deleted_time` (`tenant_id`, `deleted`, `submit_time`)
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
    KEY `idx_ticket` (`ticket_id`, `create_time`),
    -- 看板的"超时工单数"从动作侧找（超时动作是稀有事件，V5，docs/08）
    KEY `idx_action_ticket` (`action`, `ticket_id`)
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

-- 工单协作者（多人同做一单，docs/01 §4.5）
CREATE TABLE `ticket_collaborator` (
    `id`          bigint   NOT NULL COMMENT '主键（雪花）',
    `tenant_id`   bigint   NOT NULL COMMENT '租户ID',
    `ticket_id`   bigint   NOT NULL COMMENT '工单ID',
    `worker_id`   bigint   NOT NULL COMMENT '协作维修工ID（主责仍然是 ticket.worker_id，不是这个）',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '加入时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_ticket_worker` (`ticket_id`, `worker_id`),
    -- 数据权限拦截器按 worker_id 反查"我协作的工单"，师傅端每个工单查询都会用到（V6，docs/08）
    KEY `idx_worker` (`worker_id`, `ticket_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='工单协作者（多人同做一单，docs/01 §4.5）';

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

-- ============================================================
-- 五、操作审计日志（账号与基础数据的写操作留痕，docs/01 §4.4）
-- ============================================================
--
-- 三条与其它表不同的取舍：**不记工单流转**（ticket_log 更细，重复记两份等于两份真相）、
-- **存操作人与目标的名字快照**（主数据改名后历史仍可读）、**只 insert，没有 update_time / deleted**。
--
-- 这里没写 COLLATE：与平台库的建表语句保持一致——它跟随**所在库的默认排序规则**。
-- 租户库的默认排序规则是从平台库抄过来的（见 TenantDatabaseProvisioner），所以两边的
-- audit_log 排序规则相同；这正是 `TenantSchemaParityTest` 逐列比对时要看到的结果。
CREATE TABLE `audit_log` (
    `id`            bigint       NOT NULL                COMMENT '雪花 ID',
    `tenant_id`     bigint       NOT NULL                COMMENT '租户ID（平台运营自己的操作是 0）',
    `operator_id`   bigint       NOT NULL                COMMENT '操作人',
    `operator_name` varchar(50)  NOT NULL                COMMENT '操作人姓名快照（账号改名后记录仍可读）',
    `action`        varchar(64)  NOT NULL                COMMENT '动作码，如 WORKER_CREATE / TENANT_DISABLE',
    `target_type`   varchar(32)  DEFAULT NULL            COMMENT '目标类型：WORKER / STUDENT / BUILDING / CATEGORY / REPAIR_CODE / TENANT / ACCOUNT；登录事件没有目标，为 NULL',
    `target_id`     bigint       DEFAULT NULL            COMMENT '目标 ID（批量导入这类没有单一目标时为 NULL）',
    `target_name`   varchar(100) DEFAULT NULL            COMMENT '目标名称快照：工号 / 楼栋名 / 学校名…',
    `detail`        varchar(500) DEFAULT NULL            COMMENT '一句人话摘要：做了什么、改了哪些字段（不含敏感原值）',
    `ip`            varchar(45)  DEFAULT NULL            COMMENT '来源 IP（IPv6 最长 45）',
    `create_time`   datetime     NOT NULL                COMMENT '发生时间',
    PRIMARY KEY (`id`),
    KEY `idx_tenant_create` (`tenant_id`, `create_time`),
    KEY `idx_tenant_operator_create` (`tenant_id`, `operator_id`, `create_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '操作审计日志（账号与基础数据的写操作，只增不改）';

-- ============================================================
-- 六、维修知识库（RAG 检索的原料，ADR-018）
-- ============================================================
--
-- 中文检索用 MySQL 8 自带的 ngram 全文索引（不需要外部分词器、不新增依赖）；
-- `tags` 用来补同义词，弥补关键词检索的召回弱点。设计理由见 docs/02 与 ADR-018。
CREATE TABLE `knowledge_entry` (
    `id`          bigint       NOT NULL COMMENT '主键（雪花）',
    `tenant_id`   bigint       NOT NULL COMMENT '租户ID',
    `category_id` bigint                DEFAULT NULL COMMENT '挂到报修类别；NULL = 通用条目（任何类别都可能命中）',
    `title`       varchar(64)  NOT NULL COMMENT '标题，如「水管接头漏水」',
    `symptom`     varchar(255) NOT NULL COMMENT '现象 / 适用场景——检索主字段，写用户会说的那种话',
    `solution`    text         NOT NULL COMMENT '处理步骤，一行一步（前端按行拆开展示）',
    `tags`        varchar(128)          DEFAULT NULL COMMENT '关键词（空格分隔）。**用来补同义词**，弥补关键词检索召回弱',
    `status`      tinyint      NOT NULL DEFAULT 1 COMMENT '1 启用 / 0 停用',
    `sort`        int          NOT NULL DEFAULT 0 COMMENT '同分时的展示顺序',
    `create_time` datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否 1是',
    PRIMARY KEY (`id`),
    KEY `idx_tenant_status` (`tenant_id`, `status`),
    KEY `idx_tenant_category` (`tenant_id`, `category_id`),
    FULLTEXT KEY `ft_knowledge` (`title`, `symptom`, `tags`) WITH PARSER ngram
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='维修知识库（RAG 检索的原料，ADR-018）';

SET FOREIGN_KEY_CHECKS = 1;
