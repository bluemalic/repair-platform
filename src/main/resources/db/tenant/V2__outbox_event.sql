-- 可靠事件发件箱（阶段 1）：与平台库同结构，TenantSchemaParityTest 会逐列逐索引比对
CREATE TABLE `outbox_event` (
    `id`              bigint       NOT NULL COMMENT '主键（雪花）',
    `tenant_id`       bigint       NOT NULL COMMENT '租户ID',
    `aggregate_type`  varchar(32)  NOT NULL COMMENT '聚合类型，如 TICKET',
    `aggregate_id`    bigint       NOT NULL COMMENT '聚合ID',
    `event_type`      varchar(64)  NOT NULL COMMENT '事件类型，如 TICKET_STATUS_CHANGED',
    `event_id`        varchar(64)  NOT NULL COMMENT '事件唯一ID（消费幂等用）',
    `payload`         json         NOT NULL COMMENT '事件载荷',
    `status`          varchar(16)  NOT NULL DEFAULT 'NEW' COMMENT 'NEW / SENDING / SENT',
    `attempts`        int          NOT NULL DEFAULT 0 COMMENT '已重试次数',
    `next_attempt_at` datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下次可投递时间',
    `created_at`      datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `sent_at`         datetime     DEFAULT NULL COMMENT '投递成功时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_event_id` (`event_id`),
    KEY `idx_status_next` (`status`, `next_attempt_at`),
    KEY `idx_aggregate` (`aggregate_type`, `aggregate_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='可靠事件发件箱';
