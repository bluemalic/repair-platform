package com.bluemalic.repair.tenancy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 把**存量租户**的数据从平台库搬进它自己的库（阶段 6 第 2 步 · ADR-021 决定 7b）。
 *
 * <p>做三件事，都是幂等的，所以可以反复跑：
 * <ol>
 *   <li>{@link #copy}：逐表"删目标 → 从源库搬"（{@code INSERT INTO 新库.表 SELECT … FROM 平台库.表 WHERE tenant_id = ?}）；</li>
 *   <li>{@link #verify}：逐表比对**行数 + 全列校验和**（外加两侧的列结构），一张表都不能漏、不能多；</li>
 *   <li>{@link #isSwitching}：翻转期间把该租户的请求挡在门外（见 {@link #markSwitching}）。</li>
 * </ol>
 *
 * <p><b>为什么每条 SQL 都是"跨库一句话"</b>：{@code INSERT INTO a.t SELECT … FROM b.t} 在同一条语句里
 * 完成读取与写入，**单表原子**——一张表要么整张搬过去、要么什么都没发生，不会留下半张表。
 * 于是"搬到一半进程被杀"的结果是"有些表搬完了、有些没动"，重跑一遍即可（每张表都是先删目标再搬）。
 *
 * <p><b>为什么要比校验和而不只比行数</b>：行数只能发现"少搬了"，发现不了"搬串了"——
 * 而按位置列映射（{@code INSERT … SELECT *}）一旦两侧列顺序不同就会**静默错位**。
 * 所以校验和用**两侧的列清单**拼出来（列名与顺序先比对过），比对的是每一行的全部列值。
 * 它挡的是三类事故：漏搬、源库在搬完之后又被写过、两侧表结构不一致。
 *
 * <p><b>为什么不用数据权限拦截器/MyBatis 做这件事</b>：这是跨库的运维动作（一次 SQL 里出现两个库名），
 * 而 MyBatis 的每个 Mapper 都绑在"当前请求该用的那个库"上；表名与过滤条件来自下面这份
 * **硬编码计划**（没有任何用户输入），所以拼 SQL 在这里是安全的，且比"给 16 张表各写一个 Mapper 方法"清楚得多。
 * 同类先例：{@code TenantDatabaseProvisioner} 的 DDL、{@code AiReadOnlyAccountBootstrap} 的 GRANT。
 */
@Slf4j
@Component
public class TenantDataMigrator {

    /**
     * 逐表搬运计划：表名 + 过滤条件模板。
     *
     * <p>{@code {s}} 是库名占位（同一个条件要在源库与目标库两侧都成立），{@code ?} 是租户 ID 占位。
     *
     * <p>**顺序只在两处有意义**：`sys_user_role` 与 `sys_role_permission` 的条件用子查询指向
     * `sys_user` / `sys_role`，所以它们必须排在那两张表之后（删除时按相反顺序，这两张先删）。
     * 其余表之间没有引用（项目不用物理外键），顺序只为日志好读。
     *
     * <p>**没有 `sys_permission`**：权限点是平台定义的数据，两边都由种子脚本保证（`TenantSchemaParityTest`
     * 逐行比对过），搬它不是"搬这个租户的数据"，而是把平台的定义复制一份。
     *
     * <p>**没有 `tenant_sso_config` 与 `tenant`**：它们是"登录之前就要读"的平台专属表
     * （见 {@code PlatformOnlyTables}），租户库里根本没有这两张表。
     */
    private static final List<CopyTable> PLAN = List.of(
            new CopyTable("sys_role", "tenant_id = ?"),
            new CopyTable("sys_user", "tenant_id = ?"),
            new CopyTable("sys_user_role", "user_id IN (SELECT id FROM {s}.sys_user WHERE tenant_id = ?)"),
            new CopyTable("sys_role_permission", "role_id IN (SELECT id FROM {s}.sys_role WHERE tenant_id = ?)"),
            new CopyTable("building", "tenant_id = ?"),
            new CopyTable("worker_building", "tenant_id = ?"),
            new CopyTable("worker_skill", "tenant_id = ?"),
            new CopyTable("ticket_category", "tenant_id = ?"),
            new CopyTable("repair_code", "tenant_id = ?"),
            new CopyTable("ticket", "tenant_id = ?"),
            new CopyTable("ticket_log", "tenant_id = ?"),
            new CopyTable("ticket_evaluation", "tenant_id = ?"),
            new CopyTable("ticket_collaborator", "tenant_id = ?"),
            new CopyTable("notification", "tenant_id = ?"),
            new CopyTable("audit_log", "tenant_id = ?"),
            new CopyTable("knowledge_entry", "tenant_id = ?"),
            // 发件箱也搬：切换前还没投递的事件不能丢（SENT 的历史行一并复制，目标库 relay 只处理 NEW）。
            // 幂等记录也搬：切换后同一个 eventId 不会被重复消费。
            new CopyTable("outbox_event", "tenant_id = ?"),
            new CopyTable("consumed_event", "tenant_id = ?"));

    private static final String COLUMNS_SQL =
            "SELECT ORDINAL_POSITION, COLUMN_NAME, COLUMN_TYPE FROM information_schema.COLUMNS"
                    + " WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? ORDER BY ORDINAL_POSITION";

    private final DataSource controlDataSource;

    /**
     * 正在翻转的租户。用内存集合而不是 Redis：这是**单实例**部署，而且它只活几秒——
     * 放 Redis 反而多一次网络往返，还要考虑"进程崩了谁清这个标记"。
     */
    private final Set<Long> switching = ConcurrentHashMap.newKeySet();

    public TenantDataMigrator(@Qualifier("controlDataSource") DataSource controlDataSource) {
        this.controlDataSource = controlDataSource;
    }

    /** 搬运计划覆盖的表名（给测试用：漏一张表就该红）。 */
    public List<String> plannedTables() {
        return PLAN.stream().map(CopyTable::table).toList();
    }

    /** 该租户是否正在翻转路由（翻转期间拒它的请求，把"窗口期"缩到几毫秒）。 */
    public boolean isSwitching(long tenantId) {
        return switching.contains(tenantId);
    }

    /** 进入翻转窗口（调用方：{@code TenantDatabaseServiceImpl.switchToDedicated}，用 try/finally 配对） */
    public void markSwitching(long tenantId) {
        switching.add(tenantId);
    }

    /** 退出翻转窗口：**必须放在 finally 里**，否则该校的请求会被一直挡着 */
    public void unmarkSwitching(long tenantId) {
        switching.remove(tenantId);
    }

    /**
     * 逐表搬运：**先删目标库里这个租户的行，再从源库搬**。
     *
     * <p>删而不是追加，是为了让"重跑"的语义明确：目标是这个租户数据的**副本**，
     * 每次重跑都重新对齐源库。追加上去的话，源库删过的行会在目标库里留着——
     * 那种不一致不会报错，只会在切换之后以"多出来的数据"出现。
     */
    public Map<String, Long> copy(long tenantId, String schema) {
        TenantSchemas.requireSafe(schema);
        String source = controlDatabase();
        Map<String, Long> copied = new LinkedHashMap<>();
        // 删除按相反顺序：子查询条件依赖的两张表要留到最后删
        for (int i = PLAN.size() - 1; i >= 0; i--) {
            CopyTable table = PLAN.get(i);
            execute("DELETE FROM `" + schema + "`.`" + table.table() + "` WHERE "
                    + predicate(table, schema), tenantId);
        }
        for (CopyTable table : PLAN) {
            long rows = execute("INSERT INTO `" + schema + "`.`" + table.table()
                    + "` SELECT * FROM `" + source + "`.`" + table.table() + "` WHERE "
                    + predicate(table, source), tenantId);
            copied.put(table.table(), rows);
            log.info("搬运完成 tenantId={} schema={} table={} rows={}", tenantId, schema, table.table(), rows);
        }
        return copied;
    }

    /**
     * 逐表校验：两侧的**列结构**必须一致，**行数**与**全列校验和**都必须相等。
     *
     * <p>只读，不写任何库——所以它可以在学校正常使用时随时跑（校验用的是普通一致读，不加锁）。
     */
    public List<TableCheck> verify(long tenantId, String schema) {
        TenantSchemas.requireSafe(schema);
        String source = controlDatabase();
        List<TableCheck> checks = new ArrayList<>();
        for (CopyTable table : PLAN) {
            List<String> sourceColumns = columns(source, table.table());
            List<String> targetColumns = columns(schema, table.table());
            if (!sourceColumns.equals(targetColumns)) {
                checks.add(TableCheck.structureMismatch(table.table(), sourceColumns, targetColumns));
                continue;
            }
            long[] sourceStat = countAndChecksum(source, table, tenantId, schema);
            long[] targetStat = countAndChecksum(schema, table, tenantId, schema);
            checks.add(new TableCheck(table.table(), sourceStat[0], targetStat[0],
                    String.valueOf(sourceStat[1]), String.valueOf(targetStat[1]), true));
        }
        return checks;
    }

    /** 目标库建出来没有（搬家会建它；切换前要用它挡"还没搬就切"）。 */
    public boolean databaseExists(String schema) {
        TenantSchemas.requireSafe(schema);
        try (Connection connection = controlDataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT COUNT(*) FROM information_schema.SCHEMATA WHERE SCHEMA_NAME = ?")) {
            statement.setString(1, schema);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() && rs.getInt(1) > 0;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("查询目标库是否存在失败：" + schema, e);
        }
    }

    /** 平台库的库名（从连接上取，不让配置里重复写一遍）。 */
    public String controlDatabase() {
        try (Connection connection = controlDataSource.getConnection()) {
            return connection.getCatalog();
        } catch (SQLException e) {
            throw new IllegalStateException("取平台库库名失败：" + e.getMessage(), e);
        }
    }

    // ==================== SQL 拼装与执行 ====================

    private String predicate(CopyTable table, String schema) {
        return table.predicate().replace("{s}", schema);
    }

    /** 行数 + 全列校验和：{@code SUM(CRC32(每行各列拼起来))}，两侧用同一个表达式。 */
    private long[] countAndChecksum(String schema, CopyTable table, long tenantId, String checksumSchema) {
        List<String> columnNames = columnNames(columns(checksumSchema, table.table()));
        StringBuilder expression = new StringBuilder("SUM(CRC32(CONCAT_WS('#', ");
        for (int i = 0; i < columnNames.size(); i++) {
            if (i > 0) {
                expression.append(", ");
            }
            expression.append("CAST(`").append(columnNames.get(i)).append("` AS CHAR)");
        }
        expression.append(")))");
        String sql = "SELECT COUNT(*), COALESCE(" + expression + ", 0) FROM `" + schema + "`.`"
                + table.table() + "` WHERE " + predicate(table, schema);
        try (Connection connection = controlDataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, tenantId);
            try (ResultSet rs = statement.executeQuery()) {
                if (!rs.next()) {
                    throw new IllegalStateException("校验查询没有返回行：" + sql);
                }
                return new long[]{rs.getLong(1), rs.getBigDecimal(2) == null ? 0 : rs.getBigDecimal(2).longValue()};
            }
        } catch (SQLException e) {
            throw new IllegalStateException("校验失败 schema=" + schema + " table=" + table.table()
                    + "（" + e.getMessage() + "）", e);
        }
    }

    /** 列清单（顺序敏感）：拼成 {@code 序号:列名:类型}，用于两侧结构比对与校验和表达式。 */
    private List<String> columns(String schema, String table) {
        List<String> columns = new ArrayList<>();
        try (Connection connection = controlDataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(COLUMNS_SQL)) {
            statement.setString(1, schema);
            statement.setString(2, table);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    columns.add(rs.getInt(1) + ":" + rs.getString(2) + ":" + rs.getString(3));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读列结构失败 schema=" + schema + " table=" + table, e);
        }
        return columns;
    }

    private List<String> columnNames(List<String> columns) {
        return columns.stream().map(column -> column.split(":")[1]).toList();
    }

    /** 执行一句跨库 SQL，返回影响行数（搬运统计要用它）。 */
    private long execute(String sql, long tenantId) {
        try (Connection connection = controlDataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, tenantId);
            return statement.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("搬运 SQL 执行失败：" + sql + "（" + e.getMessage() + "）", e);
        }
    }

    /** 一张表的搬运计划 */
    private record CopyTable(String table, String predicate) {
    }

    /** 一张表的校验结果 */
    public record TableCheck(String table, long sourceRows, long targetRows,
                             String sourceChecksum, String targetChecksum, boolean structureMatches) {

        static TableCheck structureMismatch(String table, List<String> source, List<String> target) {
            return new TableCheck(table, -1, -1, "-", "-", false);
        }

        /** 两侧一致：结构相同、行数相同、全列校验和相同 */
        public boolean consistent() {
            return structureMatches && sourceRows == targetRows && sourceChecksum.equals(targetChecksum);
        }
    }
}
