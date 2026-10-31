-- 设备台账（阶段 3 · docs/01 §4.8）。
-- 租户侧的表：平台库与租户库各一份，列必须一致（TenantSchemaParityTest 会逐列比对）。
CREATE TABLE `asset` (
    `id`             bigint       NOT NULL COMMENT '设备ID',
    `tenant_id`      bigint       NOT NULL COMMENT '租户ID',
    `asset_no`       varchar(64)  NOT NULL COMMENT '设备编号（租户内唯一；二维码内容）',
    `name`           varchar(64)  NOT NULL COMMENT '设备名称，如「3号楼201空调」',
    `category_id`    bigint       DEFAULT NULL COMMENT '类别ID（ticket_category）',
    `building_id`    bigint       DEFAULT NULL COMMENT '楼栋ID',
    `room`           varchar(32)  DEFAULT NULL COMMENT '房间/位置，如「201」',
    `status`         tinyint      NOT NULL DEFAULT 1 COMMENT '状态 1在用 2维修中 3停用/报废',
    `purchase_date`  date         DEFAULT NULL COMMENT '采购日期',
    `warranty_until` date         DEFAULT NULL COMMENT '保修截止日期',
    `supplier`       varchar(64)  DEFAULT NULL COMMENT '供应商',
    `remark`         varchar(255) DEFAULT NULL COMMENT '备注',
    `deleted`        tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否 1是',
    `create_time`    datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `update_time`    datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_tenant_asset_no` (`tenant_id`, `asset_no`),
    KEY `idx_tenant_building` (`tenant_id`, `building_id`),
    KEY `idx_tenant_status` (`tenant_id`, `status`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='设备台账';

ALTER TABLE `ticket`
    ADD COLUMN `asset_id` bigint DEFAULT NULL COMMENT '关联设备ID' AFTER `building_id`,
    ADD KEY `idx_asset` (`asset_id`);
