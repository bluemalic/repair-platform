package com.bluemalic.repair;

import com.bluemalic.repair.tenancy.TenantDatabaseProvisioner;
import com.bluemalic.repair.tenancy.TenantRoutingContext;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 测试用真租户库夹具（阶段 2.3）：建 {@link #SCHEMA}、跑 {@code db/tenant} 迁移、灌固定业务数据。
 *
 * <p><b>为什么不碰 `tenant` 表</b>：一是不该为了测试改演示租户 gdou（id = 1）的路由——
 * 整套集成测试里有大量用例依赖"gdou 的数据在平台库"（演示种子、平台账号、审计），
 * 把它指到独立库会连带改几十个用例的语义；二是**不能**额外登记一个测试租户：
 * 平台运营端的租户列表会把每一行都当学校展示，多一行就多一次"解析它的库"，
 * 在测试事务里直接触发"事务中不许切库"（10005）。
 *
 * <p>所以这里只保证 {@code repair_gdou} 这个库存在且数据齐；要跑在它上面的用例
 * 标 {@link TenantTestDb}，由监听器把路由键设过去。生产路径（catalog 按 `tenant.db_schema`
 * 解析）由 {@code TenantRoutingTest} 里那些"开通学校"的用例覆盖。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TestTenantBootstrap {

    /** 灌数据用的 tenant_id：与平台种子里的 0 / 1 错开，避免和已有行抢主键。 */
    public static final long TENANT_ID = 9100L;

    /**
     * 测试专用的库名，**刻意不与演示库 `repair_gdou` 同名**：本地开发机上那个库可能装着
     * 手工验证用的演示数据（`docs/05` §OAuth2 手工链路就写的是它），夹具会删表里的行，
     * 撞上去就把演示数据洗掉了。
     */
    public static final String SCHEMA = "repair_test_gdou";

    private final TenantDatabaseProvisioner databaseProvisioner;

    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    void provision() {
        databaseProvisioner.createAndMigrate(SCHEMA);
        seedBusinessData();
        log.info("测试真租户库已就绪 schema={}", SCHEMA);
    }

    /**
     * 灌最小可用的基础数据。租户库的 R__seed 只有角色与权限点（没有楼栋/类别/报修码），
     * 而工单相关用例需要它们。
     *
     * <p>先按 tenant_id 删再插（而不是 `INSERT IGNORE`）：主键是固定的小整数，重复运行时
     * 如果上一次留下的是**别的 tenant_id** 的行，IGNORE 会把新数据静默丢掉，测试就变成
     * "库里明明有 6 条、查询却一条也读不到"。
     */
    private void seedBusinessData() {
        TenantRoutingContext.run(SCHEMA, () -> {
            jdbcTemplate.update("DELETE FROM building WHERE tenant_id = ?", TENANT_ID);
            jdbcTemplate.update("DELETE FROM ticket_category WHERE tenant_id = ?", TENANT_ID);
            jdbcTemplate.update("DELETE FROM repair_code WHERE tenant_id = ?", TENANT_ID);

            jdbcTemplate.update("INSERT INTO building (id, tenant_id, name, area, sort, status) VALUES "
                    + "(1, 9100, '1号楼', '东区', 1, 1), (2, 9100, '2号楼', '东区', 2, 1), "
                    + "(3, 9100, '3号楼', '西区', 3, 1), (4, 9100, '4号楼', '西区', 4, 1), "
                    + "(5, 9100, '图书馆', '中心区', 5, 1)");
            jdbcTemplate.update("INSERT INTO ticket_category (id, tenant_id, name, default_urgency, sort, status) "
                    + "VALUES (1, 9100, '水电', 2, 1, 1), (2, 9100, '家具', 1, 2, 1), (3, 9100, '网络', 2, 3, 1), "
                    + "(4, 9100, '门锁', 2, 4, 1), (5, 9100, '空调', 1, 5, 1), (6, 9100, '其他', 1, 6, 1)");
            jdbcTemplate.update("INSERT INTO repair_code "
                    + "(id, tenant_id, code, building_id, room, location_type, status) VALUES "
                    + "(1, 9100, '482913', 1, '1-101', 1, 1), (2, 9100, '751204', 2, '2-201', 1, 1), "
                    + "(3, 9100, '306718', 3, '3-412', 1, 1), (4, 9100, '925146', 4, '4-305', 1, 1), "
                    + "(5, 9100, '640372', 5, '301', 1, 1), (6, 9100, '731205', 5, '1F-大厅', 2, 1), "
                    + "(7, 9100, '518340', 1, '3F-走廊', 2, 1), (8, 9100, '609217', 2, '1F-公共卫生间', 2, 1)");
        });
    }
}
