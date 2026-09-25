package com.bluemalic.repair.ai;

import com.bluemalic.repair.common.BizException;
import com.bluemalic.repair.common.ErrorCode;
import com.bluemalic.repair.config.AiProperties;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLTimeoutException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

/**
 * 用**只读账号**执行 AI 生成的 SQL——ADR-003 四层闸门里的第 1 层（连接层）与第 4 层（超时、行数上限）。
 *
 * <p><b>为什么自己持有一个小连接池，而不是再声明一个 Spring {@code DataSource} bean</b>：
 * 现在数据源完全由自动装配持有，加第二个 bean 就得给原来那个加 {@code @Primary}（改到既有装配）。
 * 自己持有还有个更重要的好处：**结构上保证 AI 的 SQL 永远不可能走到 root 连接上**——
 * 它连的是另一个账号、另一个池，不经过 MyBatis，也不可能被谁改成走主库。
 *
 * <p>连接串从既有的 {@code spring.datasource.url} 派生（只换账号），不重复拼 host/port/db：
 * 两处各写一份的话，改一处忘一处就会出现"问数连到别的库上"这种极难查的问题。
 *
 * <p>超时与行数上限（第 4 层）都在这里：{@code setQueryTimeout} 让 MySQL 侧中断，
 * {@code setMaxRows(maxRows + 1)} 让驱动最多只取这么多行——**多取的那一行是用来判断"是否被截断"的**
 * （取到 maxRows + 1 行说明还有更多，返回时只给 maxRows 行并置 {@code rowLimited}）。
 */
@Slf4j
@Component
public class AiQueryExecutor implements DisposableBean {

    private final AiProperties aiProperties;

    private final String jdbcUrl;

    /** 懒建：没配只读账号就不建池，也不占连接。 */
    private volatile HikariDataSource readOnlyPool;

    public AiQueryExecutor(AiProperties aiProperties,
                           @Value("${spring.datasource.url}") String jdbcUrl) {
        this.aiProperties = aiProperties;
        this.jdbcUrl = jdbcUrl;
    }

    /**
     * 执行一条**已经过安全网关**的 SQL。超时 → {@code 40003}；其它执行失败 → {@code 40001}。
     *
     * <p>为什么"执行失败"用 {@code 40001}（自然语言无法解析为查询意图）：最典型的原因是模型
     * 编了个不存在的字段（{@code Unknown column 'xxx'}），也就是"这个问题没能变成一条能跑的查询"——
     * 与 40001 的语义一致。**不新造错误码**是项目约定，所以在这个码上补一句更具体的话，
     * 并把触发场景写进 docs/03。
     */
    public AiQueryResult execute(String sql) {
        try (Connection connection = readOnlyPool().getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setQueryTimeout(aiProperties.queryTimeoutSeconds());
            statement.setMaxRows(aiProperties.getMaxRows() + 1);
            try (ResultSet resultSet = statement.executeQuery()) {
                return read(resultSet);
            }
        } catch (SQLTimeoutException e) {
            log.warn("AI 问数超时（阈值 {} 秒）sql={}", aiProperties.queryTimeoutSeconds(), sql);
            throw new BizException(ErrorCode.AI_QUERY_TIMEOUT);
        } catch (SQLException e) {
            // 完整信息进日志，给用户的只说"换个说法"——SQL 的原始报错对用户没有指导意义
            log.warn("AI 生成的 SQL 执行失败 sql={} err={}", sql, e.getMessage());
            throw new BizException(ErrorCode.AI_INTENT_UNRESOLVED, "这条问题没能变成可执行的查询，请换个说法再问一次");
        }
    }

    private AiQueryResult read(ResultSet resultSet) throws SQLException {
        ResultSetMetaData meta = resultSet.getMetaData();
        int columnCount = meta.getColumnCount();
        List<String> columns = new ArrayList<>(columnCount);
        for (int i = 1; i <= columnCount; i++) {
            columns.add(meta.getColumnLabel(i));
        }

        int maxRows = aiProperties.getMaxRows();
        List<List<Object>> rows = new ArrayList<>();
        boolean rowLimited = false;
        while (resultSet.next()) {
            if (rows.size() >= maxRows) {
                // 多取到的那一行：说明后面还有，标记截断后停止读取（不再往内存里堆）
                rowLimited = true;
                break;
            }
            List<Object> row = new ArrayList<>(columnCount);
            for (int i = 1; i <= columnCount; i++) {
                row.add(normalize(resultSet.getObject(i)));
            }
            rows.add(row);
        }
        return new AiQueryResult(columns, rows, rowLimited);
    }

    /**
     * 归一化 JDBC 返回的值，两个理由：
     *
     * <ol>
     *   <li><b>数字必须是数字</b>：本项目的全局 Jackson 规则把 {@code Long} 序列化成**字符串**
     *       （雪花 ID 超出 JS 安全整数，不转字符串会丢精度）。但问数结果里的 {@code COUNT(*)} 是
     *       给人看、给 ECharts 用的，变成 {@code "42"} 之后图会画不出来。所以整数/浮点一律转成
     *       {@link BigDecimal}——它不受那条规则影响，也不会像 {@code double} 那样在计数上丢精度</li>
     *   <li><b>时间要能被 Jackson 格式化</b>：{@code java.sql.Timestamp} 不在项目配置的
     *       {@code LocalDateTime} 规则里，转过去才与其它接口的时间格式一致</li>
     * </ol>
     */
    private Object normalize(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Timestamp timestamp) {
            return timestamp.toLocalDateTime();
        }
        if (value instanceof java.sql.Date date) {
            return date.toLocalDate();
        }
        if (value instanceof java.sql.Time time) {
            return time.toLocalTime();
        }
        if (value instanceof BigInteger bigInteger) {
            return new BigDecimal(bigInteger);
        }
        if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long) {
            return BigDecimal.valueOf(((Number) value).longValue());
        }
        if (value instanceof Float || value instanceof Double) {
            return BigDecimal.valueOf(((Number) value).doubleValue());
        }
        // BigDecimal / String / Boolean 等原样返回
        return value;
    }

    private HikariDataSource readOnlyPool() {
        HikariDataSource pool = readOnlyPool;
        if (pool == null) {
            synchronized (this) {
                if (readOnlyPool == null) {
                    readOnlyPool = buildPool();
                }
                pool = readOnlyPool;
            }
        }
        return pool;
    }

    private HikariDataSource buildPool() {
        if (!aiProperties.readOnlyAccountConfigured()) {
            // 与"模型不可用"同一个码：对调用方来说都是"AI 问数这会儿用不了"，文案说清是没配账号
            throw new BizException(ErrorCode.AI_MODEL_UNAVAILABLE,
                    "AI 问数未启用（服务器上没配只读数据库账号）");
        }
        HikariConfig config = new HikariConfig();
        config.setPoolName("AiReadOnlyPool");
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(aiProperties.getDbUsername());
        config.setPassword(aiProperties.getDbPassword());
        // 问数是低频交互，2 条连接够用；池小也让"AI 不会跟主业务抢连接"这件事变得显然
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(3000);
        // 连接层再声明一次只读（第 1 层的补充：账号权限是第一道，这是第二道）
        config.setReadOnly(true);
        log.info("AI 只读连接池已建立 username={}", aiProperties.getDbUsername());
        return new HikariDataSource(config);
    }

    @Override
    public void destroy() {
        if (readOnlyPool != null) {
            readOnlyPool.close();
        }
    }
}
