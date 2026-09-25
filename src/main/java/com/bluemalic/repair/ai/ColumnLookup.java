package com.bluemalic.repair.ai;

/**
 * "某张表有没有某个列"的查询能力。
 *
 * <p>存在的原因：安全网关需要在 SQL 上注入 {@code deleted = 0}（见 {@link SqlSafetyGateway}），
 * 但**能不能注入取决于那张表有没有这一列**——注给没有该列的表会让 SQL 直接报错。
 * 网关本身是纯逻辑类（不碰数据库、可单测），所以这个判断做成一个它依赖的接口：
 * 生产环境由 {@link SchemaCatalog} 从 {@code information_schema} 提供，测试里给个假实现。
 */
@FunctionalInterface
public interface ColumnLookup {

    /** 表名与列名都用小写比较。 */
    boolean has(String table, String column);
}
