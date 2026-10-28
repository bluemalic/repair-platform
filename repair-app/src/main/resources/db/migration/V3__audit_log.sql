-- ============================================================
-- V3 · 操作审计日志：账号与基础数据的写操作留痕
-- ============================================================
--
-- 背景：工单流转有自己的台账（ticket_log），但**账号与基础数据的改动一点痕迹都没有**——
-- 停用一位师傅、改一个学生的姓名、重置谁的口令、把某栋楼停用，事后只能靠人回忆。
-- 学校真用起来之后一定会有人问"这个账号谁停用的"，这张表就是唯一的凭据（docs/01 §4.4）。
--
-- 三条设计上的取舍，别照着"日志表"的直觉改：
--
-- 1. **不记工单流转**。ticket_log 比这里更细（from/to 状态 + 动作 + 操作人），
--    重复记两份等于两份可能不一致的真相。审计表的范围是"没有台账的那些写操作"。
-- 2. **存操作人与目标的名字快照**。账号改名、楼栋被删之后，光看 id 什么也读不出来；
--    审计表是只读的历史，不能跟着主数据一起变。
-- 3. **没有 update_time、没有 deleted**（与其它表不同）。能改、能删的记录不是审计记录：
--    这张表只 insert，永不 update/delete。要归档就按 create_time 定期导出（docs/01 §4.4）。
--
-- 不存的东西：口令明文（含新口令）、手机号等敏感字段的原值——摘要里只说"手机号已更新"。
-- 审计表也是表，落进去就收不回来了（AGENTS §5.9）。
-- ============================================================

CREATE TABLE `audit_log` (
    `id`            bigint       NOT NULL                COMMENT '雪花 ID',
    `tenant_id`     bigint       NOT NULL                COMMENT '租户ID（平台运营自己的操作是 0）',
    `operator_id`   bigint       NOT NULL                COMMENT '操作人',
    `operator_name` varchar(50)  NOT NULL                COMMENT '操作人姓名快照（账号改名后记录仍可读）',
    `action`        varchar(64)  NOT NULL                COMMENT '动作码，如 WORKER_CREATE / TENANT_DISABLE',
    `target_type`   varchar(32)  NOT NULL                COMMENT '目标类型：WORKER / STUDENT / BUILDING / CATEGORY / REPAIR_CODE / TENANT / ACCOUNT / USER',
    `target_id`     bigint       DEFAULT NULL            COMMENT '目标 ID（批量导入这类没有单一目标时为 NULL）',
    `target_name`   varchar(100) DEFAULT NULL            COMMENT '目标名称快照：工号 / 楼栋名 / 学校名…',
    `detail`        varchar(500) DEFAULT NULL            COMMENT '一句人话摘要：做了什么、改了哪些字段（不含敏感原值）',
    `ip`            varchar(45)  DEFAULT NULL            COMMENT '来源 IP（IPv6 最长 45）',
    `create_time`   datetime     NOT NULL                COMMENT '发生时间',
    PRIMARY KEY (`id`),
    -- 审计页按"本租户 + 时间倒序"翻页，这条索引直接覆盖它
    KEY `idx_tenant_create` (`tenant_id`, `create_time`),
    -- "某个人最近干了什么"是第二常见的问法
    KEY `idx_tenant_operator_create` (`tenant_id`, `operator_id`, `create_time`)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = '操作审计日志（账号与基础数据的写操作，只增不改）';
