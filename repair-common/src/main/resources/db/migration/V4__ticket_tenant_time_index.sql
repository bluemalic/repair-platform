-- ============================================================
-- V4 · 给 ticket 补一条"本租户 + 未删除 + 按提交时间倒序"的索引
-- ============================================================
--
-- 背景：压测发现的（docs/08）。22k 工单时，管理端工单列表的 EXPLAIN 是
--     type=ref  key=idx_tenant_status  rows=11079  Extra=Using filesort
-- 也就是：先按 tenant_id 取出本租户一万多行，**再在内存里排序**才能给出第 1 页那 10 条。
-- 单请求 P50 76ms、并发下 P99 620ms（目标 300ms），看板同样受影响。
--
-- 为什么会缺：现有索引都差那么一列——
--   idx_tenant_status(tenant_id, status, submit_time)：中间夹着 status，而默认列表不筛状态，
--     索引的第三列就用不上了（索引只在"前导列全等值或范围"时才有序）
--   idx_student(student_id, submit_time)、idx_worker_status(worker_id, status, submit_time)：
--     分别服务学生端与维修工端，管理端用不上
-- 而管理端的默认列表恰好是**不筛任何状态**的。
--
-- 为什么把 deleted 放第二列而不是最后：它是「等值条件」，放在 tenant_id 之后、
-- submit_time（范围条件）之前，MySQL 才能把两个等值 + 一个范围都用上；
-- 放最后就只能靠回表过滤，等于白加。
--
-- 这一条索引同时服务三件事（索引不是越多越好，它得能说清服务谁）：
--   ① 管理端默认列表（本租户 + 未删除 + 按提交时间倒序，LIMIT 10 能提前停）
--   ② 看板的时间范围聚合（trend / overview 都以 submit_time 为区间条件）
--   ③ 按状态筛选的列表（状态作为索引上的过滤条件，仍按提交时间序取）
-- ============================================================

ALTER TABLE `ticket`
    ADD INDEX `idx_tenant_deleted_time` (`tenant_id`, `deleted`, `submit_time`);
