-- ADR-026 收口：学校行必须有 db_schema。
-- 演示学校 gdou 用 `<control>`（与控制库同库）作为演示夹具；真实学校开通时写独立库名。
-- 其余学校行如果 db_schema 仍为空，说明是遗留的假数据，直接删除（本项目当前没有真实存量数据）。
UPDATE `tenant` SET `db_schema` = '<control>' WHERE `id` = 1 AND `db_schema` IS NULL;

DELETE FROM `tenant`
 WHERE `id` NOT IN (0, 1)
   AND (`db_schema` IS NULL OR `db_schema` = '');

ALTER TABLE `tenant`
    ADD CONSTRAINT `chk_tenant_db_schema` CHECK (`id` = 0 OR `db_schema` IS NOT NULL);
