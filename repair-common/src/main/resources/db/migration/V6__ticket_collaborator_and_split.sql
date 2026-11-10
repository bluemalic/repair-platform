-- ============================================================
-- V6 · 协同处理：协作者关系表 + 拆单来源列（docs/01 §4.5）
-- ============================================================
--
-- 背景：主线是"一个人一单"，但现实里有两类例外——一件活要两个人一起干，
-- 和一张单里其实是两件事。前者加协作者，后者拆单，这次两样一起做。
--
-- 为什么协作者要单独一张表，而不是 ticket 上加一列：
--   一条工单可以有多人参与（最多 3 个协作者），一列装不下；而且"谁参与"是关系、不是工单的属性。
--   表形态照 worker_building（纯关联表）：带 tenant_id，不带 update_time / deleted——
--   移除一个协作者就是删掉这行，留痕在 ticket_log 里（那里才是"发生过什么"的地方）。
--
-- 为什么需要有 idx_worker 这条反向索引：
--   数据权限拦截器要按 worker_id 反查"我协作的工单"，给维修工的可见范围加第三层
--   （负责楼栋 OR 派给我的 OR 我协作的，docs/01 §4.2）。这条索引是它唯一的用途，
--   也是本次改动最需要盯的性能点——它会被注入到师傅端的每一个工单查询里（复测见 docs/08）。
--   uk_ticket_worker 是"同一张单同一个人只能加一次"的兜底（并发下靠数据库，不靠先查再插）。
-- ============================================================

CREATE TABLE `ticket_collaborator` (
    `id`          bigint   NOT NULL COMMENT '主键（雪花）',
    `tenant_id`   bigint   NOT NULL COMMENT '租户ID',
    `ticket_id`   bigint   NOT NULL COMMENT '工单ID',
    `worker_id`   bigint   NOT NULL COMMENT '协作维修工ID（主责仍然是 ticket.worker_id，不是这个）',
    `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '加入时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_ticket_worker` (`ticket_id`, `worker_id`),
    KEY `idx_worker` (`worker_id`, `ticket_id`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='工单协作者（多人同做一单，docs/01 §4.5）';

-- 拆单来源：拆出来的新单指向原单。**只拆一层**（拆出来的单不能再拆），
-- 所以这是一个自关联的父指针，不是一棵树——别按树去写查询。
-- 不加索引：目前只有"按 id 查父单"（走主键）这一个场景，没有"查某单拆出了哪些子单"的查询。
-- 真要做那类查询（比如详情页列出子单）时再加，不预先建——索引也要能说清服务谁。
ALTER TABLE `ticket`
    ADD COLUMN `parent_ticket_id` bigint DEFAULT NULL COMMENT '拆单来源工单ID，非拆单产生的为 NULL' AFTER `worker_id`;
