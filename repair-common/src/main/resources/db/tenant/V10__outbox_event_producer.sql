-- 事件生产者标识（阶段 3.3 · ADR-033）：与平台库 V24 保持一致，TenantSchemaParityTest 会逐列逐索引比对
--
-- 列追加在末尾（不写 AFTER）：两份脚本的位置写死容易漂，按"都追加到最后"来保证顺序一致。
ALTER TABLE `outbox_event`
    ADD COLUMN `producer` varchar(32) NOT NULL DEFAULT 'ticket' COMMENT '事件生产者：ticket / identity / notification / ai';
