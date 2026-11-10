-- 与平台库 V19 保持一致：TenantSchemaParityTest 会逐列逐索引比对两套脚本的结果。
ALTER TABLE `ticket`
    ADD COLUMN `merged_into_id` bigint DEFAULT NULL
        COMMENT '合并目标工单ID（75 已合并时指向被合并进的工单，其余为 NULL）' AFTER `parent_ticket_id`,
    MODIFY COLUMN `status` tinyint NOT NULL DEFAULT 10
        COMMENT '状态 10待派单 20待接单 30处理中 35待料 40待验收 45无法维修 50已完成 60已关闭 70已撤单 75已合并 80已驳回';
