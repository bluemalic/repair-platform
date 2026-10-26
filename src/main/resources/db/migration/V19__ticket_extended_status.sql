-- 扩展状态（docs/01 §4.7）：35 待料 / 45 无法维修 / 75 已合并
--
-- merged_into_id 只在 75 已合并时有值，指向"被合并进的那张单"；它和 parent_ticket_id（拆单来源）
-- 是两件事：拆单是"一张变两张"，合并是"两张并一张"，方向与语义都不同。
ALTER TABLE `ticket`
    ADD COLUMN `merged_into_id` bigint DEFAULT NULL
        COMMENT '合并目标工单ID（75 已合并时指向被合并进的工单，其余为 NULL）' AFTER `parent_ticket_id`,
    MODIFY COLUMN `status` tinyint NOT NULL DEFAULT 10
        COMMENT '状态 10待派单 20待接单 30处理中 35待料 40待验收 45无法维修 50已完成 60已关闭 70已撤单 75已合并 80已驳回';
