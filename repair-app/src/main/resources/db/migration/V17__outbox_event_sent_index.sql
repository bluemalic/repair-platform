-- SENT 事件的保留期清理：`WHERE status = 'SENT' AND sent_at < ?`
-- 原有 idx_status_next 的第二列是 next_attempt_at，按 sent_at 的范围过滤用不上它。
-- 非 SENT 行的 sent_at 为空，在索引最前面，范围扫描会直接跳过，代价很小。
ALTER TABLE `outbox_event` ADD KEY `idx_sent_at` (`sent_at`);
