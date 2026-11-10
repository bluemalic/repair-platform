-- 与平台库 V16 保持一致：TenantSchemaParityTest 会逐索引比对两套脚本的结果。
-- 租户库里的 consumed_event 同样由独立进程写入，同样需要保留期清理的访问路径。
ALTER TABLE `consumed_event` ADD KEY `idx_consumed_at` (`consumed_at`);
