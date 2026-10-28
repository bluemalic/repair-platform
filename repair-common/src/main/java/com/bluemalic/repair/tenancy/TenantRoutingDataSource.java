package com.bluemalic.repair.tenancy;

import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;

import javax.sql.DataSource;
import java.util.Map;

/**
 * 按当前请求的租户把连接**路由到对应租户库**的数据源（阶段 6 · ADR-021）。全应用只有这一个
 * `DataSource`（`@Primary`），MyBatis 与事务管理器都用它——所以"一次业务 = 一个库里的一个事务"。
 *
 * <p><b>为什么重写 {@code determineTargetDataSource} 而不是用父类的 targetDataSources 映射</b>：
 * 父类那套映射是在 `afterPropertiesSet()` 时**拷一份快照**的，而租户池是**运行时懒建**的
 * （今天开通的学校，明天才有人访问）。父类还带一个 `lenientFallback`：找不到键就悄悄用默认库——
 * 那正是"配错了也不报错、只是读到别家数据"的形态（spike 里有反例实测）。
 *
 * <p>所以路由决策在这里显式写成三段，语义上等于 `lenientFallback = false` 且更严：
 * <ol>
 *   <li>键为空 → 平台库（无登录态的系统上下文、登录接口本身）；</li>
 *   <li>键是 {@link TenantRoutingContext#CONTROL} → 平台库（控制面读写、存量租户在过渡期里的家）；</li>
 *   <li>其它 → 那个租户库的池。**没有第四段**：不存在"键不认识就用默认库"这条路。</li>
 * </ol>
 *
 * <p>"键不认识"这件事在实现里被前移到了 {@link TenantSchemaCatalog}：路由键由租户行推导而来，
 * 租户不存在时它直接抛错。所以这里拿到的一定是一个已开通学校的库名；万一那个库真的不存在，
 * MySQL 会在连接时明确报 `Unknown database`——**报错，而不是静默换库**。
 */
@Slf4j
public class TenantRoutingDataSource extends AbstractRoutingDataSource {

    private final DataSource controlDataSource;

    private final TenantDataSourceRegistry registry;

    public TenantRoutingDataSource(DataSource controlDataSource, TenantDataSourceRegistry registry) {
        this.controlDataSource = controlDataSource;
        this.registry = registry;
        initParent();
    }

    /** 只在诊断日志里用得上（真正决定连接的是 {@link #determineTargetDataSource}） */
    @Override
    protected Object determineCurrentLookupKey() {
        return TenantRoutingContext.currentKey();
    }

    @Override
    protected DataSource determineTargetDataSource() {
        String key = TenantRoutingContext.currentKey();
        if (key == null || TenantRoutingContext.CONTROL.equals(key)) {
            return controlDataSource;
        }
        return registry.dataSourceFor(key);
    }

    /**
     * 让父类把它的静态映射机制初始化掉（父类要求 `targetDataSources` 非空，而本项目不用它）。
     *
     * <p>放在构造里是有意的：这个类只由 {@code TenantDataSourceConfig} 手工 new 出来，
     * 不走组件扫描——**没有"忘调用"的可能**，而漏调用会让父类的 `getConnection` 直接抛
     * `IllegalArgumentException`（启动即失败，不是运行时）。
     */
    private void initParent() {
        setTargetDataSources(Map.of());
        afterPropertiesSet();
    }
}
