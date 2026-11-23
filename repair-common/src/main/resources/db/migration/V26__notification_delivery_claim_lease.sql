-- Add the transient SENDING state used by the atomic delivery claim lease.
ALTER TABLE `notification_delivery`
    MODIFY COLUMN `notification_id` bigint DEFAULT NULL
        COMMENT '关联站内信ID（外部重试从此取回正文）',
    MODIFY COLUMN `status` varchar(16) NOT NULL DEFAULT 'PENDING'
        COMMENT 'PENDING / SENDING / SENT / FAILED / DEAD';
