-- ============================================================
-- V8 · 维修知识库 knowledge_entry（阶段 2 · ADR-018）
-- ============================================================
--
-- 背景：师傅在工单详情里点「排查建议」时，系统拿这张表检索，命中的条目作为**参考资料**
-- 交给模型，模型只负责"把资料组织成人话 + 标出引用"——建议不是模型想出来的，是从这里查出来的。
--
-- 【为什么中文检索用 ngram 全文索引，而不是 embedding 向量】
--   MySQL 8 自带 `WITH PARSER ngram`：把文本切成 n-gram（默认 2）建倒排索引，
--   于是 `MATCH ... AGAINST('宿舍门锁转不动')` 对中文直接可用，**不需要外部分词器、不新增任何依赖**。
--   实测（2026-10-17，本地 MySQL 8，ngram_token_size=2）：4 条样本条目下，
--   口语长句「我们宿舍的门锁好像坏了，钥匙插进去转不动」只命中「门锁卡住」（score 3.62），
--   无关查询「电梯坏了」零命中——正是想要的行为：没答案就说没答案。
--   不做向量检索的理由（项目唯一的模型 provider 没有 embedding 接口、Redis 是官方 7-alpine
--   没有 RediSearch、知识库规模是"一所学校几十条"）见 ADR-018，接口已留位。
--
-- 【为什么用 `status` 停用而不是删除】
--   条目会被**历史建议**引用（回头看"当时为什么给这条建议"），删掉就断了线索；
--   所以业务上一律用 `status` 停用，`deleted` 只留给"确实要清理的脏数据"。
--
-- 【`tags` 不是可有可无的装饰】
--   关键词检索最大的弱点是**同义词召回**（"渗水" 命中不了 "漏水"）。这是这套方案里
--   刻意留给人的一个旋钮：管理员把同义词补进 tags 即可。管理端表单要写清这一点。
--
-- 【租户隔离】本表**不在**数据权限拦截器的覆盖名单里（那张名单只有 `ticket` 与 `notification`），
--   所以所有查询**必须显式写 `tenant_id`**（AGENTS §5.6）——知识库是每所学校自己的积累。
-- ============================================================

CREATE TABLE `knowledge_entry` (
    `id`          bigint       NOT NULL COMMENT '主键（雪花）',
    `tenant_id`   bigint       NOT NULL COMMENT '租户ID',
    `category_id` bigint                DEFAULT NULL COMMENT '挂到报修类别；NULL = 通用条目（任何类别都可能命中）',
    `title`       varchar(64)  NOT NULL COMMENT '标题，如「水管接头漏水」',
    `symptom`     varchar(255) NOT NULL COMMENT '现象 / 适用场景——检索主字段，写用户会说的那种话',
    `solution`    text         NOT NULL COMMENT '处理步骤，一行一步（前端按行拆开展示）',
    `tags`        varchar(128)          DEFAULT NULL COMMENT '关键词（空格分隔）。**用来补同义词**，弥补关键词检索召回弱',
    `status`      tinyint      NOT NULL DEFAULT 1 COMMENT '1 启用 / 0 停用',
    `sort`        int          NOT NULL DEFAULT 0 COMMENT '同分时的展示顺序',
    `create_time` datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `update_time` datetime     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    `deleted`     tinyint      NOT NULL DEFAULT 0 COMMENT '逻辑删除 0否 1是',
    PRIMARY KEY (`id`),
    KEY `idx_tenant_status` (`tenant_id`, `status`),
    KEY `idx_tenant_category` (`tenant_id`, `category_id`),
    FULLTEXT KEY `ft_knowledge` (`title`, `symptom`, `tags`) WITH PARSER ngram
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='维修知识库（RAG 检索的原料，ADR-018）';
