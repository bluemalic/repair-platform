package com.bluemalic.repair.ai;

import java.util.List;
import java.util.Set;

/**
 * 问数的**表白名单**与**敏感列黑名单**——ADR-003 四层闸门里第 2 层（SQL 白名单）与第 1 层
 * （只读账号授权）共用的唯一来源。
 *
 * <p><b>为什么是代码常量，而不是从 information_schema 读出来</b>：白名单是安全边界的一部分。
 * 若从库里读，那么"加一张表"就自动放开了它对 AI 的可见性——那正是"配置漂移导致越权"的经典形态，
 * 而且漂移发生时没有任何人做决定。所以这里是**默认拒绝**：新表必须显式加进 {@link #TABLES}。
 *
 * <p><b>为什么白名单与授权同源</b>：只读数据库账号的 GRANT 语句由 {@link #grantStatements} 从
 * 这份清单生成（见 {@code AiReadOnlyAccountBootstrap}）。如果两处各写一份，"代码允许查的表"
 * 与"账号被授权的表"迟早会不一致——不一致的两种方向都糟：账号权限更大 = 闸门形同虚设，
 * 代码白名单更大 = 查询报权限错误而看不出原因。
 *
 * <p><b>选表的原则</b>：只放**业务分析真正需要的、带 {@code tenant_id} 的**表。所以
 * {@code notification}（个人收件箱，不是分析对象）与 {@code tenant}（平台层，不属于任何租户）
 * 都不在名单里。**每张白名单表都必须有 {@code tenant_id} 列**——第 3 层靠它注入租户条件，
 * 没有这一列的表根本没法限定租户，{@code SqlSafetyGateway} 会因此拒绝查询。
 */
public final class SchemaWhitelist {

    /**
     * 允许 AI 查询的表（默认拒绝，只增不改）。
     *
     * <p>顺序不影响安全性，但影响提示词里的呈现顺序——按"工单 → 字典 → 人"排，读起来顺。
     */
    private static final List<String> TABLES = List.of(
            "ticket",
            "ticket_log",
            "ticket_evaluation",
            "ticket_category",
            "building",
            "worker_building",
            "sys_user");

    private static final Set<String> TABLE_SET = Set.copyOf(TABLES);

    /**
     * 敏感列：**出现在生成的 SQL 里就整条拒绝**，与表是否在白名单内无关。
     *
     * <p>为什么需要单独一层：表白名单挡不住 {@code SELECT phone FROM sys_user}——表是白名单里的，
     * 但列不是该给问数看的东西。这一层与"不打日志"那条约定同源（AGENTS §5.8）：
     * 手机号这类信息不该因为"多了个问数功能"而多出一次暴露机会。
     */
    private static final Set<String> SENSITIVE_COLUMNS = Set.of("password", "phone");

    private SchemaWhitelist() {
    }

    /** 允许查询的表（只读视图，调用方不要改）。 */
    public static List<String> tables() {
        return TABLES;
    }

    /**
     * 表是否在白名单内。**归一化后精确匹配**：小写、去反引号。
     *
     * <p>带库名前缀的（{@code repair.ticket}）**不通过**——点号保留在名字里，匹配不上就拒绝。
     * 这是有意的：白名单是"精确允许"，不做"看起来像是同一张表"的推断。
     */
    public static boolean isAllowed(String tableName) {
        return tableName != null && TABLE_SET.contains(normalize(tableName));
    }

    /** 这个词是不是敏感列（词法层面的判断，由 {@code SqlSafetyGateway} 做分词）。 */
    public static boolean isSensitiveColumn(String token) {
        return token != null && SENSITIVE_COLUMNS.contains(token.toLowerCase());
    }

    /** 小写 + 去反引号。表名比较统一走这里，避免"大小写不同就当两张表"。 */
    public static String normalize(String tableName) {
        return tableName.replace("`", "").trim().toLowerCase();
    }

    /**
     * 生成只读账号的授权语句：**只 GRANT SELECT，且只到白名单表**。
     *
     * <p>不授 {@code SELECT} 到整个库——那样加一张新表就自动对 AI 可见了，与上面"默认拒绝"同一条理由。
     * 也不授任何写权限与 DDL：这是 ADR-003 第 1 层的全部内容（数据库权限层面兜底，
     * 就算前一层白名单被绕过，改数据/删表也做不到）。
     *
     * <p>授权对象写 {@code 'user'@'%'}：MySQL 里没有"参数化 DDL"，用户名只能拼进语句，
     * 所以这里**先校验用户名再拼**（不依赖调用方记得校验）。{@code '%'} 是容器部署下的实用选择——
     * 应用从 docker 网络里连过来，宿主机看到的来源地址不稳定；风险由"只授 7 张表的 SELECT +
     * 强口令 + MySQL 只监听 127.0.0.1"一起兜住。
     */
    public static List<String> grantStatements(String database, String username) {
        if (username == null || !username.matches("[A-Za-z0-9_]{1,32}")) {
            throw new IllegalArgumentException("只读账号名只允许字母/数字/下划线，最长 32 位");
        }
        return TABLES.stream()
                .map(table -> "GRANT SELECT ON `" + database + "`.`" + table + "` TO '" + username + "'@'%'")
                .toList();
    }
}
