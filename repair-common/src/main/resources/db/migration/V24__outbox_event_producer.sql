-- 事件生产者标识（阶段 3.3 · ADR-033）
--
-- 为什么现在加：阶段 2 时只有一个进程写 outbox（工单命令路径），"谁产生的"能从
-- aggregate_type + event_type 推出来。抽出 ticket / identity 之后，同一个库里的 outbox
-- 会有多个写者，排查"这条事件是谁写的、该不该由它重试"时没有这一列只能看 payload 猜。
--
-- 默认值 'ticket' 是为了兼容线上已有行与仍在用旧签名写入的调用点（老代码不传 producer）。
-- 列追加在末尾（不写 AFTER）：平台库与租户库两份脚本必须逐列一致，位置写死容易漂。
ALTER TABLE `outbox_event`
    ADD COLUMN `producer` varchar(32) NOT NULL DEFAULT 'ticket' COMMENT '事件生产者：ticket / identity / notification / ai';
