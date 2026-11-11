package com.bluemalic.repair.ai;

import com.bluemalic.repair.config.AiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.List;

/**
 * 只读数据库账号的引导创建——ADR-003 四层闸门的**第 1 层**（数据库权限层面兜底）。
 *
 * <p><b>为什么由应用建，而不是让运维照文档执行一段 SQL</b>：那是个"记得做"的步骤，
 * 而漏了它的现象是"问数接口报连接失败"——要绕一圈才知道是账号没建。同一个坑刚踩过：
 * 演示站重建前端时漏了 {@code VITE_DEMO_ACCOUNTS}，站能打开但访客不知道怎么登录。
 * 交给启动时引导，配了 {@code AI_DB_*} 就一定会有账号，且白名单与授权**同源**
 * （GRANT 由 {@link SchemaWhitelist} 生成，不会出现"代码允许查的表"与"账号被授权的表"漂开）。
 *
 * <p><b>与平台运营账号的引导有一条相反的规则</b>：那个账号"只建不改"（它的口令是人的登录凭据，
 * 覆盖会把手改过的口令打回环境变量值）；这个账号**每次启动都同步口令**——它是应用自己要用的
 * 凭据，{@code .env} 就是它的唯一真相来源，改了就该生效，不需要运维再手工跟一次。
 *
 * <p>权限每次启动**收敛**而不是累加：先 {@code REVOKE ALL}，再按白名单 {@code GRANT SELECT}。
 * 这样"有人手工给过额外权限"也会被自动收回——白名单是唯一真相。
 *
 * <p>失败不阻断启动（照 {@code DemoResetJob} / {@code PlatformBootstrapJob} 的取舍）：
 * AI 用不了是"少一个功能"，不该让整个系统起不来。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiReadOnlyAccountBootstrap {

    /** 只读账号名与口令都来自配置，但 DDL 里没有参数化，所以只允许"安全字符集"的名字。 */
    private static final String SAFE_USERNAME = "[A-Za-z0-9_]{1,32}";

    private final DataSource dataSource;

    private final AiProperties aiProperties;

    @EventListener(ApplicationReadyEvent.class)
    public void ensureAccountOnStartup() {
        if (!aiProperties.readOnlyAccountConfigured()) {
            return;
        }
        try {
            ensureAccount(aiProperties.getDbUsername(), aiProperties.getDbPassword());
        } catch (Exception e) {
            log.error("只读数据库账号引导失败（AI 问数会用不了，其余功能不受影响）", e);
        }
    }

    /**
     * 幂等地把只读账号建成"白名单 SELECT 权限"的样子。
     *
     * <p>暴露成 public 是为了让集成测试能直接调（并断言"能读白名单表、不能写、不能读别的表"）。
     *
     * <p>这个重载只管**当前连接的那个库**：测试与单库场景够用。多库要传库名清单，
     * 见 {@link #ensureAccount(String, String, Collection)} —— 授权是"先 REVOKE ALL 再按清单 GRANT"，
     * 所以**必须一次把库名给全**，分几次调会把前一次授的权限洗掉。
     */
    public void ensureAccount(String username, String password) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            ensureAccount(username, password, List.of(connection.getCatalog()));
        }
    }

    /**
     * 把只读账号的权限**收敛**成"这些库的白名单表的 SELECT"（阶段 6 · ADR-021：每个学校一个库）。
     *
     * <p>{@code databases} 里应当是**全部要授权的库**：平台库（控制面 + 过渡期里存量租户的数据）
     * 与每个租户库。先 {@code REVOKE ALL} 再逐个 GRANT，所以漏传一个库＝那所学校的 AI 问数报权限错误，
     * 多传一个已删掉的库则无害（MySQL 允许给不存在的库授权）。
     */
    public void ensureAccount(String username, String password, Collection<String> databases) throws SQLException {
        requireSafeUsername(username);
        String userAtHost = "'" + username + "'@'%'";
        String escapedPassword = escapeLiteral(password);

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("CREATE USER IF NOT EXISTS " + userAtHost
                    + " IDENTIFIED BY '" + escapedPassword + "'");
            statement.execute("ALTER USER " + userAtHost + " IDENTIFIED BY '" + escapedPassword + "'");
            statement.execute("REVOKE ALL PRIVILEGES, GRANT OPTION FROM " + userAtHost);

            int granted = 0;
            for (String database : databases) {
                for (String grant : SchemaWhitelist.grantStatements(requireSafeDatabase(database), username)) {
                    statement.execute(grant);
                    granted++;
                }
            }
            statement.execute("FLUSH PRIVILEGES");
            log.info("只读数据库账号已就绪 username={} 库={} 授权表数={}",
                    username, databases, granted);
        }
    }

    /**
     * 启动/开通学校之后把授权刷成"全部库"的形态。没配只读账号时什么都不做。
     *
     * <p>失败只记 ERROR 不抛：授权失败＝AI 用不了，不该让应用起不来（与 {@link #ensureAccountOnStartup} 同一取舍）。
     */
    public void ensureAccountOnDatabases(Collection<String> databases) {
        if (!aiProperties.readOnlyAccountConfigured()) {
            return;
        }
        try {
            ensureAccount(aiProperties.getDbUsername(), aiProperties.getDbPassword(), databases);
        } catch (Exception e) {
            log.error("只读数据库账号授权失败（AI 问数会用不了，其余功能不受影响）databases={}", databases, e);
        }
    }

    /** 名字直接拼进 DDL，所以先按白名单校验字符集——不依赖调用方记得校验。 */
    private void requireSafeUsername(String username) {
        if (username == null || !username.matches(SAFE_USERNAME)) {
            throw new IllegalArgumentException("只读账号名只允许字母/数字/下划线，最长 32 位");
        }
    }

    /**
     * 库名同样要拼进 DDL（`GRANT ... ON \`库名\`.\`表\`），所以也校验字符集。
     *
     * <p>库名的**来源**（`tenant.db_schema`）在写入时已经校验过（{@code TenantSchemas}），这里再挡一道
     * 是防"将来有人从别处传进来"。与账号名一样，DDL 里没有占位符可用。
     */
    private String requireSafeDatabase(String database) {
        if (database == null || !database.matches("[A-Za-z0-9_]{1,64}")) {
            throw new IllegalArgumentException("库名只允许字母/数字/下划线，最长 64 位：" + database);
        }
        return database;
    }

    /**
     * 口令要拼进 SQL 字面量（MySQL 的 DDL 不接受占位符）。
     *
     * <p>按 MySQL 字符串字面量的规则转义：反斜杠与单引号。不转义的话，口令里带一个单引号
     * 就会让语句提前结束——轻则建号失败，重则把后面那截当 SQL 执行。
     */
    private String escapeLiteral(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("只读账号口令不能为空");
        }
        return raw.replace("\\", "\\\\").replace("'", "''");
    }
}
