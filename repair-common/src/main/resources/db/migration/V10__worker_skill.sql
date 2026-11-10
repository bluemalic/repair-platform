-- ============================================================
-- V10 · 维修工擅长类别（阶段 3 · 派单评分的"技能"维度，ADR-019）
-- ============================================================
--
-- 为什么单独一张关联表，而不是给 sys_user 加一个逗号分隔的标签字段：
--   · "擅长哪些类别"是多值关系，查"谁擅长水电"要能走索引，标签字段只能全表 like；
--   · 类别停用/改名时，关联表天然跟着 `ticket_category` 走，不用改字符串；
--   · 形态照 worker_building（同样的"人能干什么"关系）：带 tenant_id、没有 update_time / deleted——
--     取消一个技能就是删掉这行。
--
-- 只建 `uk_worker_category`：它同时服务两个查询——"这个师傅擅长什么"（评分时按 worker_id IN 批量取）
-- 与"同一个人同一个类别只能有一条"。**不建 category_id 单列索引**：目前没有"按类别反查师傅"的查询，
-- 索引要能说清服务谁（docs/02 §4）。
-- ============================================================

CREATE TABLE `worker_skill` (
    `id`          bigint   NOT NULL COMMENT '主键（雪花）',
    `tenant_id`   bigint   NOT NULL COMMENT '租户ID',
    `worker_id`   bigint   NOT NULL COMMENT '维修工用户ID',
    `category_id` bigint   NOT NULL COMMENT '擅长类别（ticket_category.id）',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '设置时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_worker_category` (`worker_id`, `category_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='维修工擅长类别（派单评分的技能维度，ADR-019）';
