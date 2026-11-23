-- Keep tenant notification_delivery schema aligned with platform schema.
ALTER TABLE `notification_delivery`
    MODIFY COLUMN `notification_id` bigint DEFAULT NULL
        COMMENT '关联站内信ID（外部重试从此取回正文）',
    MODIFY COLUMN `status` varchar(16) NOT NULL DEFAULT 'PENDING'
        COMMENT 'PENDING / SENDING / SENT / FAILED / DEAD';
