package com.bluemalic.repair.config;

import com.bluemalic.repair.tenancy.TenantDataSourceRegistry;
import com.bluemalic.repair.tenancy.TenantRoutingDataSource;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.autoconfigure.flyway.FlywayDataSource;
import org.springframework.boot.autoconfigure.jdbc.DataSourceProperties;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;

/**
 * 数据源的装配：**一个平台库主池 + 一个按租户路由的数据源**（阶段 6 · ADR-021）。
 *
 * <p>全应用有两个 `DataSource` bean，但只有一个会被 MyBatis / 事务管理器 / 健康检查拿到：
 * <ul>
 *   <li>{@link #controlDataSource} —— 平台库（`repair`）的主池。**只给 Flyway 用**：
 *       控制面的迁移必须在平台库上跑，而平台库是过渡期里存量租户的家；</li>
 *   <li>{@link #routingDataSource} —— `@Primary`，给整个应用用。它按当前请求的租户
 *       把连接路由到对应租户库（{@code TenantRoutingDataSource}）。</li>
 * </ul>
 *
 * <p><b>为什么 Flyway 要单独钉住</b>：`spring.flyway` 的自动装配本来会去拿"唯一的那个 DataSource"，
 * 现在有两个了，它可能拿到路由数据源——那就变成"谁在启动时先设了路由键，就在谁家的库上建表"。
 * 加 {@code @FlywayDataSource} 是 Boot 留给这种情况的显式指定方式，让这件事不依赖 bean 的先后顺序。
 *
 * <p><b>没有做的两件事，都是有意的</b>：
 * <ol>
 *   <li>不给租户库单独建数据库账号（"第二道防线"）：它需要一套凭据保管与轮换机制，
 *       当前部署的应用账号本来就有全部库的权限，加了账号也挡不住路由写错——推迟，见 ADR-021；</li>
 *   <li>不做"每租户 12 条连接"：2C2G 上连接是共享资源，租户池是小池 + 懒建 + 空闲回收
 *       （见 {@code TenancyProperties}）。</li>
 * </ol>
 */
@Configuration
public class TenantDataSourceConfig {

    /**
     * 平台库主池。参数沿用 `application.yml` 里既有的 `spring.datasource.*`
     * （`@ConfigurationProperties` 绑 `spring.datasource.hikari.*`，与 Boot 自动装配同一套写法，
     * 所以 `pool-name / maximum-pool-size` 这些配置项一个都不用改、也没搬过位置）。
     */
    @Bean
    @FlywayDataSource
    @ConfigurationProperties("spring.datasource.hikari")
    public HikariDataSource controlDataSource(DataSourceProperties properties) {
        return properties.initializeDataSourceBuilder().type(HikariDataSource.class).build();
    }

    /**
     * 应用唯一的数据源（`@Primary`）：按租户路由。
     *
     * <p>MyBatis 的 `SqlSessionFactory`、事务管理器、健康检查都由 Boot 按 "single candidate" 取它，
     * 所以**不需要额外配置**。也正因为业务事务都绑在它身上，"在事务里换租户"会被
     * {@code TenantRoutingContext} 显式拒绝（换不了连接，只会静默写错库）。
     */
    @Bean
    @Primary
    public DataSource routingDataSource(DataSource controlDataSource, TenantDataSourceRegistry registry) {
        return new TenantRoutingDataSource(controlDataSource, registry);
    }
}
