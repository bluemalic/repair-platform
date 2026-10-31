-- 工种分组 / 排班（阶段 3 · docs/01 §4.8）。
-- 只服务派单候选的排序：不改变工单状态机，不做全自动派单。
CREATE TABLE `worker_group` (
    `id`          bigint      NOT NULL COMMENT '分组ID',
    `tenant_id`   bigint      NOT NULL COMMENT '租户ID',
    `name`        varchar(32) NOT NULL COMMENT '工种名，如「水电工」「空调工」',
    `sort`        int         NOT NULL DEFAULT 0 COMMENT '排序，小的在前',
    `status`      tinyint     NOT NULL DEFAULT 1 COMMENT '状态 1启用 0停用',
    `deleted`     tinyint     NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否 1是',
    `create_time` datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_tenant_status` (`tenant_id`, `status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='工种分组';

-- 维修工与工种是多对多；解除关系就是删行，所以没有 deleted。
CREATE TABLE `worker_group_member` (
    `id`          bigint   NOT NULL COMMENT '主键',
    `tenant_id`   bigint   NOT NULL COMMENT '租户ID',
    `worker_id`   bigint   NOT NULL COMMENT '维修工用户ID',
    `group_id`    bigint   NOT NULL COMMENT '工种分组ID',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_worker_group` (`worker_id`, `group_id`),
    KEY `idx_group` (`group_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='维修工所属工种';

-- 排班：某天某班次是否在班。状态 0 是「请假/休息」，与「没有排班记录」不同：
-- 没有记录按缺数据记中性分，明确休息才把候选排到最后。
CREATE TABLE `worker_shift` (
    `id`          bigint       NOT NULL COMMENT '主键',
    `tenant_id`   bigint       NOT NULL COMMENT '租户ID',
    `worker_id`   bigint       NOT NULL COMMENT '维修工用户ID',
    `shift_date`  date         NOT NULL COMMENT '日期',
    `shift_type`  tinyint      NOT NULL DEFAULT 1 COMMENT '班次 1白班 2夜班',
    `status`      tinyint      NOT NULL DEFAULT 1 COMMENT '值班状态 1在班 0请假/休息',
    `remark`      varchar(255) DEFAULT NULL COMMENT '备注',
    `create_time` datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time` datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_worker_shift` (`worker_id`, `shift_date`, `shift_type`),
    KEY `idx_tenant_date` (`tenant_id`, `shift_date`, `status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='维修工排班';

-- 类别默认对应的工种：派单评分用它判断「这类活该找哪个组」。
ALTER TABLE `ticket_category`
    ADD COLUMN `worker_group_id` bigint DEFAULT NULL COMMENT '默认工种分组ID（派单技能维度参考）' AFTER `default_urgency`,
    ADD KEY `idx_worker_group` (`worker_group_id`);
