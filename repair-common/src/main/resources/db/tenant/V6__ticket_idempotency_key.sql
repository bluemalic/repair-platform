-- 与平台库 V18 保持一致：TenantSchemaParityTest 会逐列逐索引比对两套脚本的结果。
ALTER TABLE `ticket`
    ADD COLUMN `idempotency_key` varchar(64) DEFAULT NULL
        COMMENT '提交幂等键（客户端生成；同 key 返回同一张工单）' AFTER `student_id`,
    ADD UNIQUE KEY `uk_tenant_student_key` (`tenant_id`, `student_id`, `idempotency_key`);
