-- 与平台库 V17 保持一致：TenantSchemaParityTest 会逐索引比对两套脚本的结果。
ALTER TABLE `outbox_event` ADD KEY `idx_sent_at` (`sent_at`);
