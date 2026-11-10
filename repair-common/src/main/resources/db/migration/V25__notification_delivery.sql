-- 通知投递记录（阶段 4 · ADR-030 第 3、5 条）：站内信之外的通道逐条留痕
--
-- 为什么是独立表而不是塞进 notification：站内信是**事实来源**，写失败要重试/告警；
-- 外部通道是**可选适配器**，失败只影响它自己。两者生命周期不同，混一张表会让
-- "站内信写成功、外部通道失败"没法表达。
--
-- 唯一键比 ADR-030 草稿多一列 receiver_id：一个事件可能通知多个收件人（例如"提醒全部后勤"），
-- 只按 (event_id, channel) 去重会把第二个收件人当成重复丢掉。
CREATE TABLE `notification_delivery` (
    `id`              bigint       NOT NULL COMMENT '主键（雪花）',
    `tenant_id`       bigint       NOT NULL COMMENT '租户ID',
    `event_id`        varchar(64)  NOT NULL COMMENT '来源事件ID（outbox_event.event_id）',
    `channel`         varchar(16)  NOT NULL COMMENT '通道：IN_APP / WECOM / SMS / EMAIL',
    `receiver_id`     bigint       NOT NULL COMMENT '收件人（sys_user.id）',
    `notification_id` bigint       DEFAULT NULL COMMENT '站内信ID（仅 IN_APP 有值）',
    `status`          varchar(16)  NOT NULL DEFAULT 'PENDING' COMMENT 'PENDING / SENT / FAILED / DEAD',
    `retry_count`     int          NOT NULL DEFAULT 0 COMMENT '已重试次数',
    `next_retry_at`   datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下次可重试时间（指数退避）',
    `last_error`      varchar(500) DEFAULT NULL COMMENT '最后一次失败原因（已截断，不含密钥）',
    `create_time`     datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time`     datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_event_channel_receiver` (`event_id`, `channel`, `receiver_id`),
    KEY `idx_status_next` (`status`, `next_retry_at`),
    KEY `idx_tenant_create` (`tenant_id`, `create_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='通知投递记录（外部通道为主）';
