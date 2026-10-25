-- 提交幂等（阶段 2）：同一个学生在同一个租户下用同一个 key 提交，只会产生一张工单。
--
-- 列可空是必要的：拆单产生的子工单、历史数据、演示种子都没有客户端 key。
-- MySQL 的唯一索引允许多个 NULL，所以这些行不会互相冲突。
ALTER TABLE `ticket`
    ADD COLUMN `idempotency_key` varchar(64) DEFAULT NULL
        COMMENT '提交幂等键（客户端生成；同 key 返回同一张工单）' AFTER `student_id`,
    ADD UNIQUE KEY `uk_tenant_student_key` (`tenant_id`, `student_id`, `idempotency_key`);
