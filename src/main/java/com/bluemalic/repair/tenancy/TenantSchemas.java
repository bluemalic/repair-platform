package com.bluemalic.repair.tenancy;

import java.util.regex.Pattern;

/**
 * 租户库名（schema）的命名与寻址规则——**唯一一份**。
 *
 * <p>为什么要把这两件事收在一起：库名会被拼进三处 JDBC URL（业务连接池、AI 只读池、Flyway），
 * 还要拼进 `CREATE DATABASE`。各写各的必然漂开，而漂开的后果不是报错，是"连到了别的库上"——
 * 这类问题在数据看起来正常的时候根本发现不了。
 *
 * <p><b>库名规则</b>：`{前缀}{学校编码}`，编码里的连字符换成下划线（`a-b` → `a_b`）。
 * 连字符在 MySQL 里合法，但要处处加反引号，不值得。替换会带来一次碰撞（`a-b` 与 `a_b` 同名），
 * 所以 `tenant.db_schema` 上有唯一索引：撞了就报错、由平台运营换编码，绝不静默共用一个库。
 *
 * <p><b>为什么先校验字符集再拼接</b>：库名进了 DDL 与 URL，而 DDL 不接受占位符。校验放在这里
 * （而不是依赖调用方"记得校验"），下面的 {@link #jdbcUrl} 与建库语句就都只能是安全的形状。
 */
public final class TenantSchemas {

    /** 只允许小写字母开头、小写字母/数字/下划线的库名；留出前缀的位置，总长 ≤ 64（MySQL 上限） */
    private static final Pattern SAFE_SCHEMA = Pattern.compile("^[a-z][a-z0-9_]{0,50}$");

    private TenantSchemas() {
    }

    /** 学校编码 → 库名。编码本身由开通接口的 `@Pattern` 限定为小写字母/数字/连字符。 */
    public static String forCode(String prefix, String tenantCode) {
        String name = prefix + tenantCode.replace('-', '_');
        requireSafe(name);
        return name;
    }

    /** 库名必须是安全字符集——它要拼进 DDL 与 JDBC URL，没有占位符可用。 */
    public static void requireSafe(String schema) {
        if (schema == null || !SAFE_SCHEMA.matcher(schema).matches()) {
            throw new IllegalArgumentException("租户库名只允许小写字母/数字/下划线，且以小写字母开头："
                    + schema + "（前缀见 repair.tenancy.schema-prefix）");
        }
    }

    /**
     * 由**平台库的连接串**推出某个租户库的连接串：只换库名，其余（主机、端口、参数）照抄。
     *
     * <p>抄而不是重拼：两处各写一份 host/port 的话，改一处忘一处就会出现"某个学校的库连到别的实例上"，
     * 而这种错只有当那个学校来访问时才暴露。库名以外的参数（字符集、时区、SSL）也必须完全一致，
     * 否则同一个系统在两所学校上会表现出不同的行为。
     */
    public static String jdbcUrl(String controlUrl, String schema) {
        requireSafe(schema);
        int queryStart = controlUrl.indexOf('?');
        String base = queryStart < 0 ? controlUrl : controlUrl.substring(0, queryStart);
        String query = queryStart < 0 ? "" : controlUrl.substring(queryStart);
        int lastSlash = base.lastIndexOf('/');
        // jdbc:mysql://host:port[/db]：'/' 一定在 "//" 之后；没有它说明连接串形状不对，早点报错
        if (lastSlash < 0 || lastSlash < base.indexOf("//")) {
            throw new IllegalStateException("无法从连接串推出租户库地址（没有库名段）：" + base);
        }
        return base.substring(0, lastSlash + 1) + schema + query;
    }
}
