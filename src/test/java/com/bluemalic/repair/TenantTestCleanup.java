package com.bluemalic.repair;

import com.bluemalic.repair.tenancy.TenantSchemas;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collection;

/**
 * 测试用的清理助手：把「开通学校」这类**跨库用例**开出来的东西收干净（阶段 6 · ADR-021）。
 *
 * <p>为什么需要它：开通学校要跨两个 database（平台库落租户行、租户库建管理员），塞不进一个测试事务里，
 * 所以那些用例标了 {@code NOT_SUPPORTED}——代价是它们留下的东西**不会被回滚**。
 * 不收拾的话每跑一次测试就多一所学校、一个库、一批账号；收拾的动作三个测试类都要用，所以收在这里一份。
 *
 * <p>它只用**平台库主池**直连（{@code controlDataSource}）：不经过路由，因此不受"当前上下文是哪个租户"
 * 影响，也不会因为测试没有事务而读不到刚提交的行。
 */
@Component
public class TenantTestCleanup {

    private final DataSource controlDataSource;

    public TenantTestCleanup(@Qualifier("controlDataSource") DataSource controlDataSource) {
        this.controlDataSource = controlDataSource;
    }

    /**
     * 删掉这些编码对应的学校：**先删库、再删行**（顺序反过来的话，行没了就找不回库名，库会留在服务器上）。
     * 同时删掉开通时写的那条审计（`target_type = 'TENANT'`）与该校在平台库里的账号行。
     */
    public void dropTenants(Collection<String> codes) {
        if (codes.isEmpty()) {
            return;
        }
        try (Connection connection = controlDataSource.getConnection()) {
            for (String code : codes) {
                Long tenantId = null;
                String schema = null;
                try (PreparedStatement statement = connection.prepareStatement(
                        "SELECT id, db_schema FROM tenant WHERE code = ?")) {
                    statement.setString(1, code);
                    try (ResultSet rs = statement.executeQuery()) {
                        if (rs.next()) {
                            tenantId = rs.getLong(1);
                            schema = rs.getString(2);
                        }
                    }
                }
                if (schema != null) {
                    TenantSchemas.requireSafe(schema);
                    execute(connection, "DROP DATABASE IF EXISTS `" + schema + "`");
                }
                if (tenantId != null) {
                    deleteWhere(connection, "DELETE FROM audit_log WHERE target_id = ? AND target_type = 'TENANT'",
                            tenantId);
                    // 还没搬家的租户，它的账号确实在平台库里；搬过的那些在租户库里，随库一起消失
                    deleteWhere(connection, "DELETE FROM sys_user_role WHERE user_id IN"
                            + " (SELECT id FROM sys_user WHERE tenant_id = ?)", tenantId);
                    deleteWhere(connection, "DELETE FROM sys_user WHERE tenant_id = ?", tenantId);
                    deleteWhere(connection, "DELETE FROM tenant WHERE id = ?", tenantId);
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("清理测试租户失败（测试数据没收拾干净）", e);
        }
    }

    /**
     * 按登录名前缀删测试账号（先删角色关联，别留孤儿关联）。前缀要挑本测试类独有的，以免误删别的类。
     *
     * <p><b>测试事务在场时直接跳过</b>：那种用例写的行还在事务里（未提交），
     * 用另一条连接去删会**卡在行锁上**（实测 50 秒的锁等待，把整轮测试拖成几分钟）。
     * 而且也不需要删——测试事务结束时会回滚，那些行本来就不会留下。
     */
    public void dropAccounts(String... usernamePrefixes) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            return;
        }
        try (Connection connection = controlDataSource.getConnection()) {
            for (String prefix : usernamePrefixes) {
                execute(connection, "DELETE FROM sys_user_role WHERE user_id IN"
                        + " (SELECT id FROM sys_user WHERE username LIKE '" + escapeLike(prefix) + "%')");
                execute(connection, "DELETE FROM sys_user WHERE username LIKE '" + escapeLike(prefix) + "%'");
            }
        } catch (SQLException e) {
            throw new IllegalStateException("清理测试账号失败（测试数据没收拾干净）", e);
        }
    }

    /** 前缀里只允许字母/数字/连字符/下划线，避免把通配符拼进 LIKE 里误删 */
    private String escapeLike(String prefix) {
        if (prefix == null || !prefix.matches("[A-Za-z0-9_-]{1,32}")) {
            throw new IllegalArgumentException("测试账号前缀只允许字母/数字/连字符/下划线：" + prefix);
        }
        return prefix;
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.execute();
        }
    }

    private void deleteWhere(Connection connection, String sql, long value) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setLong(1, value);
            statement.execute();
        }
    }
}
