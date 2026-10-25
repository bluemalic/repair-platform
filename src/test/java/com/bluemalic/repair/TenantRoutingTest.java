package com.bluemalic.repair;

import com.bluemalic.repair.common.BizException;
import com.bluemalic.repair.common.UserType;
import com.bluemalic.repair.entity.SysUser;
import com.bluemalic.repair.entity.SysUserRole;
import com.bluemalic.repair.entity.TicketCategory;
import com.bluemalic.repair.mapper.SysUserMapper;
import com.bluemalic.repair.mapper.SysUserRoleMapper;
import com.bluemalic.repair.mapper.TenantMapper;
import com.bluemalic.repair.mapper.TicketCategoryMapper;
import com.bluemalic.repair.tenancy.TenantDatabaseProvisioner;
import com.bluemalic.repair.tenancy.TenantRoutingContext;
import com.bluemalic.repair.tenancy.TenantSchemaCatalog;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 「每租户一个 database」的端到端验收（阶段 6 · ADR-021）。
 *
 * <p>这个类要回答的问题只有一个，但它是整个阶段的价值所在：**一所学校的请求，真的落在它自己的库上了吗？**
 * 拆成四问，每一问都必须是"两个库、两个答案"才算通过：
 *
 * <ol>
 *   <li><b>建库的形状</b>：新学校的库里有 17 张业务表 + 它自己那套迁移历史（T1 + 种子），
 *       而**没有** `tenant`（学校名录只在平台库）；</li>
 *   <li><b>写入路由</b>：同一句 SQL 在租户上下文里能查到、在平台库的上下文里查不到（反之亦然）；</li>
 *   <li><b>端到端</b>：新学校的管理员能登录（登录的第一站在平台库、第二站在它自己的库），
 *       登录后看到的是一所**空学校**——而平台库里明明躺着 gdou 的类别与工单；</li>
 *   <li><b>fail-closed</b>：未知租户、未知库名都必须**报错**，绝不能悄悄回落到平台库。</li>
 * </ol>
 *
 * <p><b>为什么这个类的用例不在测试事务里</b>：开通要跨两个 database（平台库落租户行、租户库建管理员），
 * 一个测试事务包不住它——而且 MySQL 默认 REPEATABLE READ 下，测试事务的读视图看不到"另一个事务刚提交"
 * 的租户行。所以这些用例显式 {@code NOT_SUPPORTED}，跑完自己收拾（{@link #dropProvisionedTenants()}）：
 * 连库带行一起清掉，不留给下一次运行。
 */
@IntegrationTest
class TenantRoutingTest {

    private static final long LEGACY_TENANT = 1L;
    private static final String PLATFORM_CODE = "platform";
    private static final String PASSWORD = "Test@123456";
    private static final String INITIAL_PASSWORD = "Init@123456";
    private static final String CHANGED_PASSWORD = "Changed@9876";
    private static final long ROLE_PLATFORM = 4L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TenantSchemaCatalog catalog;

    @Autowired
    private TenantDatabaseProvisioner provisioner;

    @Autowired
    private TenantMapper tenantMapper;

    @Autowired
    private TicketCategoryMapper categoryMapper;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private SysUserRoleMapper sysUserRoleMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    /** 路由数据源（@Primary）：断言"走应用真实路径读到的是哪个库"就用它 */
    @Autowired
    private DataSource dataSource;

    /** 平台库主池：清理与"平台库视角"的断言用它（不经过路由，也就不会被当前上下文影响） */
    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    @Autowired
    private TenantTestCleanup cleanup;

    /** 本类开通过的学校编码：这些用例不在测试事务里，跑完必须自己收拾 */
    private final List<String> provisionedCodes = new ArrayList<>();

    // ==================== 存量租户：照旧 ====================

    /**
     * 还没搬家的学校（R__seed 建的 gdou）路由键必须是"平台库"，行为与改动前完全一致——
     * 这是过渡期能安全上线的依据：新机制只对**新开通**的学校生效。
     */
    @Test
    void 存量租户的路由键是平台库() {
        assertThat(catalog.routingKeyFor(LEGACY_TENANT)).isEqualTo(TenantRoutingContext.CONTROL);
        // `tenant` 表只在平台库：能读到这一行，就说明这次查询确实落在平台库上
        assertThat(TenantRoutingContext.callControl(() -> tenantMapper.selectById(LEGACY_TENANT))).isNotNull();
    }

    // ==================== 新开通的学校：自己的库 ====================

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 开通学校后它的数据在自己的库里而不是平台库() throws Exception {
        String platform = givenPlatformToken("test-rt-platform");
        String code = "test-rt-" + (System.nanoTime() % 100000);
        provisionedCodes.add(code);
        String schema = provisioner.schemaFor(code);
        String adminUsername = "test-rt-admin";

        postJson(platform, "/api/platform/tenants", Map.of(
                        "name", "路由测试学校", "code", code,
                        "adminUsername", adminUsername, "adminRealName", "路由管理员",
                        "adminPassword", INITIAL_PASSWORD))
                .andExpect(jsonPath("$.code").value(0));

        long tenantId = tenantIdByCode(code);
        assertThat(dbSchemaOf(tenantId)).as("开通时把库名写进了 db_schema").isEqualTo(schema);
        assertThat(catalog.routingKeyFor(tenantId)).as("路由键 = 它自己的库名").isEqualTo(schema);

        // ① 库的形状
        assertThat(scalar(TenantRoutingContext.CONTROL,
                "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = '" + schema
                        + "' AND TABLE_NAME = 'tenant'"))
                .as("租户库里不该有 tenant（学校名录只在平台库）").isZero();
        for (String table : List.of("ticket", "sys_user", "sys_user_role", "knowledge_entry", "worker_skill",
                "outbox_event", "consumed_event")) {
            assertThat(scalar(TenantRoutingContext.CONTROL,
                    "SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = '" + schema
                            + "' AND TABLE_NAME = '" + table + "'"))
                    .as("租户库里应当有 %s", table).isEqualTo(1);
        }
        // 它跑的是**租户库自己那套脚本**（V1..V3 + R__seed 四条），与平台库的 V1..V15 不是同一个历史
        assertThat(scalar(schema, "SELECT COUNT(*) FROM flyway_schema_history"))
                .as("租户库的迁移历史只有它自己那四条").isEqualTo(4);
        assertThat(scalar(schema, "SELECT COUNT(*) FROM flyway_schema_history WHERE version = '15'"))
                .as("平台库的版本号不该出现在租户库里").isZero();

        // ② 首个管理员只在租户库：同一句 SQL、两个库、两个答案
        assertThat(scalar(schema, "SELECT COUNT(*) FROM sys_user WHERE username = '" + adminUsername + "'"))
                .as("管理员建在它自己的库里").isEqualTo(1);
        assertThat(scalar(TenantRoutingContext.CONTROL,
                "SELECT COUNT(*) FROM sys_user WHERE username = '" + adminUsername + "'"))
                .as("平台库里不该有这个管理员").isZero();

        // ③ 写入也路由：在租户上下文里插一条类别，只在那边的库里查得到
        long categoryId = TenantRoutingContext.call(schema, () -> {
            TicketCategory category = new TicketCategory();
            category.setTenantId(tenantId);
            category.setName("路由测试类别");
            category.setDefaultUrgency(1);
            category.setSort(1);
            category.setStatus(1);
            categoryMapper.insert(category);
            return category.getId();
        });
        assertThat(scalar(schema, "SELECT COUNT(*) FROM ticket_category WHERE id = " + categoryId)).isEqualTo(1);
        assertThat(scalar(TenantRoutingContext.CONTROL, "SELECT COUNT(*) FROM ticket_category WHERE id = " + categoryId))
                .as("这条类别不该出现在平台库里").isZero();

        // ④ 端到端：登录（平台库定位学校 → 租户库验账号）→ 首登改密 → 看到一所空学校
        String token = loginAfterChangingPassword(code, adminUsername);
        mockMvc.perform(get("/api/admin/tickets").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(0));
        MvcResult categories = mockMvc.perform(get("/api/categories")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        // 平台库里躺着 gdou 的 6 个类别与一批工单；这所学校看不到它们——请求确实落在它自己的库上
        assertThat(readJson(categories).path("data").size())
                .as("新学校只该看到它自己那条类别").isEqualTo(1);
    }

    /**
     * fail-closed：未知租户与未知库名都必须**报错**。
     *
     * <p>这条挡的是整套方案里最危险的一种配错：路由键查不到时"悄悄用平台库"。
     * 那样不会报错，只会让某所学校的数据写进平台库——等到有人发现数字不对时，已经分不清哪些是哪家的了。
     */
    @Test
    void 未知租户与未知库名都直接报错不会回落() {
        assertThatThrownBy(() -> catalog.routingKeyFor(999_999_999L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("重新登录");

        // 库名不认识：连不上就报错（Hikari 的报错里带着库名），而不是改用平台库
        assertThatThrownBy(() -> scalar("no_such_schema_xyz", "SELECT 1"))
                .as("未知库名必须报错")
                .hasMessageContaining("no_such_schema_xyz");
    }

    // ==================== 工具 ====================

    /** 在指定路由键下执行一句查询（走应用真实的路由数据源） */
    private int scalar(String routingKey, String sql) {
        return TenantRoutingContext.call(routingKey, () -> {
            try (Connection connection = dataSource.getConnection();
                 PreparedStatement statement = connection.prepareStatement(sql);
                 ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        });
    }

    private long tenantIdByCode(String code) throws Exception {
        // 用平台库主池直连读：这个用例没有测试事务，但直连能保证"读的是库里已提交的真相"
        try (Connection connection = controlDataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT id FROM tenant WHERE code = ?")) {
            statement.setString(1, code);
            try (ResultSet rs = statement.executeQuery()) {
                assertThat(rs.next()).as("租户 %s 应当已开通", code).isTrue();
                return rs.getLong(1);
            }
        }
    }

    private String dbSchemaOf(long tenantId) {
        return TenantRoutingContext.callControl(() -> tenantMapper.selectById(tenantId)).getDbSchema();
    }

    private String login(String tenantCode, String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "tenantCode", tenantCode, "username", username, "password", password))))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        return readJson(result).path("data").path("tokenValue").asText();
    }

    /** 首次登录强制改密，改完旧 token 失效——所以这里改完再登一次，返回新 token。 */
    private String loginAfterChangingPassword(String tenantCode, String username) throws Exception {
        String firstToken = login(tenantCode, username, INITIAL_PASSWORD);
        mockMvc.perform(put("/api/auth/password")
                        .header("Authorization", "Bearer " + firstToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "oldPassword", INITIAL_PASSWORD, "newPassword", CHANGED_PASSWORD))))
                .andExpect(jsonPath("$.code").value(0));
        return login(tenantCode, username, CHANGED_PASSWORD);
    }

    private String givenPlatformToken(String username) throws Exception {
        givenPlatformAccount(username);
        return login(PLATFORM_CODE, username, PASSWORD);
    }

    private void givenPlatformAccount(String username) {
        SysUser user = new SysUser();
        user.setTenantId(0L);
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setRealName(username);
        user.setUserType(UserType.PLATFORM.getCode());
        user.setStatus(1);
        user.setMustChangePassword(0);
        // 没有测试事务，所以这两条写会留下来——按 username 精确清理（见 dropProvisionedTenants）
        TenantRoutingContext.callControl(() -> sysUserMapper.insert(user));
        SysUserRole link = new SysUserRole();
        link.setUserId(user.getId());
        link.setRoleId(ROLE_PLATFORM);
        TenantRoutingContext.callControl(() -> sysUserRoleMapper.insert(link));
    }

    private org.springframework.test.web.servlet.ResultActions postJson(String token, String url, Map<String, ?> body)
            throws Exception {
        return mockMvc.perform(post(url)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private JsonNode readJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /**
     * 清掉本类开出来的东西。这些用例不在测试事务里（跨库流程塞不进去），留下的数据不会被回滚——
     * 不清掉的话，每跑一次就多一所学校和它的库。
     */
    @AfterEach
    void dropProvisionedTenants() {
        cleanup.dropTenants(provisionedCodes);
        cleanup.dropAccounts("test-rt-");
        provisionedCodes.clear();
    }
}
