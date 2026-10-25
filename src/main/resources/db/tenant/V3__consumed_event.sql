-- 消费幂等：与平台库同结构，TenantSchemaParityTest 会逐列逐索引比对
CREATE TABLE `consumed_event` (
    `id`             bigint      NOT NULL COMMENT '主键（雪花）',
    `tenant_id`      bigint      NOT NULL COMMENT '租户ID',
    `consumer_group` varchar(64) NOT NULL COMMENT '消费组',
    `event_id`       varchar(64) NOT NULL COMMENT '事件唯一ID（Outbox eventId / MQ msgId）',
    `consumed_at`    datetime    NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '消费时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_consumer_event` (`consumer_group`, `event_id`),
    KEY `idx_tenant_time` (`tenant_id`, `consumed_at`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='消费幂等记录';
