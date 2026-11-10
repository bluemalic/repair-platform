-- 保留期清理按 consumed_at 做全表范围删除。
-- 原有的 idx_tenant_time 前缀是 tenant_id，单独按 consumed_at 过滤用不上它，
-- 不能为了省一个索引就让每天一次的 DELETE 变成全表扫描。
ALTER TABLE `consumed_event` ADD KEY `idx_consumed_at` (`consumed_at`);
