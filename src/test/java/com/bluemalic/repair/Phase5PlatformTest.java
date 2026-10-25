package com.bluemalic.repair;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bluemalic.repair.common.UserType;
import com.bluemalic.repair.config.CacheConfig;
import com.bluemalic.repair.entity.SysUser;
import com.bluemalic.repair.entity.SysUserRole;
import com.bluemalic.repair.entity.Tenant;
import com.bluemalic.repair.mapper.SysUserMapper;
import com.bluemalic.repair.mapper.SysUserRoleMapper;
import com.bluemalic.repair.mapper.TenantMapper;
import com.bluemalic.repair.service.impl.AuthorizationCacheService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.CacheManager;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 阶段 5：租户配额 + 缓存 + 业务指标（ADR-020）。
 *
 * <p>三块各盯一条最容易错的地方：
 * <ol>
 *   <li><b>配额</b>：批量导入要按**整批**校验（不是按一条），且"已存在的学号不占新名额"</li>
 *   <li><b>缓存</b>：类别的缓存 key **必须含租户**（漏了就是跨租户泄露），写操作必须让缓存失效</li>
 *   <li><b>指标</b>：业务事件落库成功后才计数，且 `/actuator/prometheus` 真的能抓到</li>
 * </ol>
 */
@IntegrationTest
class Phase5PlatformTest {

    private static final String PASSWORD = "Test@123456";
    private static final long ROLE_STUDENT = 1L;
    private static final long ROLE_ADMIN = 3L;
    private static final long ROLE_PLATFORM = 4L;
    private static final long TENANT = 1L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private SysUserRoleMapper sysUserRoleMapper;

    @Autowired
    private TenantMapper tenantMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private AuthorizationCacheService authorizationCacheService;

    private String admin;
    private String platform;
    private long studentId;

    @BeforeEach
    void setUp() throws Exception {
        admin = givenToken(TENANT, "test-p5-admin", UserType.ADMIN, ROLE_ADMIN);
        platform = givenToken(0L, "test-p5-platform", UserType.PLATFORM, ROLE_PLATFORM);
        long id = givenAccount(TENANT, "test-p5-student", UserType.STUDENT, ROLE_STUDENT);
        studentId = id;
        // 学生账号也要能登录：本类只用到它的 userId 做缓存断言，不必真的拿 token
    }

    // ==================== 配额 ====================

    @Test
    void 学生配额用完时拒绝建号() throws Exception {
        // 上限 = 当前已用：再加一个就该被拒。**不假设库里一开始没有学生**——
        // 本机库里有演示账号，写死"上限 1"会让这条用例在库不干净时假红（踩过一次）
        setQuota((int) studentCount(), 20);
        mockMvc.perform(post("/api/admin/students")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(studentBody("test-p5-s-over"))))
                .andExpect(jsonPath("$.code").value(30004));
    }

    @Test
    void 配额还有位置时可以建号() throws Exception {
        setQuota((int) studentCount() + 1, 20);
        mockMvc.perform(post("/api/admin/students")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(studentBody("test-p5-s-ok"))))
                .andExpect(jsonPath("$.code").value(0));
    }

    /** 批量导入按**整批**校验：上限只差 1 个、一次导 2 个，必须整批拒绝且一个都不建。 */
    @Test
    void 批量导入按整批校验配额() throws Exception {
        setQuota((int) studentCount() + 1, 20); // 只留 1 个位置，却要一次导 2 个
        mockMvc.perform(post("/api/admin/students/import")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "rows", "test-p5-i1,导入学生甲\ntest-p5-i2,导入学生乙",
                                "password", PASSWORD))))
                .andExpect(jsonPath("$.code").value(30004));

        assertThat(sysUserMapper.selectCount(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getTenantId, TENANT)
                .in(SysUser::getUsername, List.of("test-p5-i1", "test-p5-i2"))))
                .as("整批被拒时一个都不该建出来").isZero();
    }

    /** 名单里已经存在的学号会被跳过，不该占新名额：上限正好等于"已用 + 新增"时应当放行。 */
    @Test
    void 导入时已存在的学号不占配额() throws Exception {
        setQuota((int) studentCount() + 1, 20); // 位置只剩 1 个，而名单里新增的正好 1 个
        mockMvc.perform(post("/api/admin/students/import")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                // 第一行是已存在的 test-p5-student（会被跳过），第二行是新学号
                                "rows", "test-p5-student,老朋友\ntest-p5-i3,新同学",
                                "password", PASSWORD))))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.created").value(1))
                .andExpect(jsonPath("$.data.skipped").value(1));
    }

    @Test
    void 维修工配额同样生效() throws Exception {
        setQuota(20, (int) workerCount() + 1); // 只留 1 个位置：第一个能建，第二个被拒
        mockMvc.perform(post("/api/admin/workers")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "test-p5-w1", "realName", "测试师傅一", "password", PASSWORD))))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(post("/api/admin/workers")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "username", "test-p5-w2", "realName", "测试师傅二", "password", PASSWORD))))
                .andExpect(jsonPath("$.code").value(30004));
    }

    /** 0 = 不限：不设上限也是合法配置（平台自用租户）。 */
    @Test
    void 配额为0表示不限() throws Exception {
        setQuota(0, 0);
        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/api/admin/students")
                            .header("Authorization", "Bearer " + admin)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(studentBody("test-p5-unlimited-" + i))))
                    .andExpect(jsonPath("$.code").value(0));
        }
    }

    @Test
    void 平台列表返回配额与用量_并且能修改配额() throws Exception {
        setQuota(111, 22);
        JsonNode row = platformTenantRow();
        assertThat(row.path("maxStudents").asInt()).isEqualTo(111);
        assertThat(row.path("maxWorkers").asInt()).isEqualTo(22);
        // 本用例事务里 setUp 建了 1 个学生；日志工单里没有维修工
        assertThat(row.path("studentCount").asLong()).isGreaterThanOrEqualTo(1);

        Map<String, Object> body = new HashMap<>();
        body.put("name", row.path("name").asText());
        body.put("maxStudents", 333);
        body.put("maxWorkers", 44);
        mockMvc.perform(put("/api/platform/tenants/" + TENANT)
                        .header("Authorization", "Bearer " + platform)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(jsonPath("$.code").value(0));

        JsonNode updated = platformTenantRow();
        assertThat(updated.path("maxStudents").asInt()).isEqualTo(333);
        assertThat(updated.path("maxWorkers").asInt()).isEqualTo(44);
    }

    // ==================== 缓存 ====================

    /**
     * 类别缓存：key 必须含租户（否则 A 校的字典会返给 B 校），写操作要让缓存失效。
     */
    @Test
    void 类别缓存按租户分键且写操作会失效() throws Exception {
        // 先读一次，让缓存里有本租户的条目
        mockMvc.perform(get("/api/categories").header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$.code").value(0));

        assertThat(cacheManager.getCache(CacheConfig.CACHE_ENABLED_CATEGORIES).get(TENANT))
                .as("本租户读一次后应进缓存").isNotNull();
        assertThat(cacheManager.getCache(CacheConfig.CACHE_ENABLED_CATEGORIES).get(999L))
                .as("别的租户不应该有这个 key（缓存 key 含租户）").isNull();

        // 新增一个类别 → 写操作清缓存 → 再读时能看到它
        mockMvc.perform(post("/api/admin/categories")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", "测试类别-p5", "defaultUrgency", 1, "sort", 99))))
                .andExpect(jsonPath("$.code").value(0));

        assertThat(cacheManager.getCache(CacheConfig.CACHE_ENABLED_CATEGORIES).get(TENANT))
                .as("写操作后缓存应被清掉").isNull();

        JsonNode list = json(mockMvc.perform(get("/api/categories")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$.code").value(0)).andReturn()).path("data");
        assertThat(list.toString()).contains("测试类别-p5");
    }

    /** 鉴权结果进缓存：同一个用户第二次取权限不再回库（这里断言缓存里有值）。 */
    @Test
    void 鉴权结果进入缓存() {
        List<String> perms = authorizationCacheService.permissionCodes(studentId);
        assertThat(perms).contains("ticket:create");
        assertThat(cacheManager.getCache(CacheConfig.CACHE_USER_PERMS).get(studentId))
                .as("用户 → 权限码 应进缓存").isNotNull();

        List<String> roles = authorizationCacheService.roleCodes(studentId);
        assertThat(roles).contains("STUDENT");
        assertThat(cacheManager.getCache(CacheConfig.CACHE_USER_ROLES).get(studentId)).isNotNull();
    }

    // ==================== 指标 ====================

    @Test
    void 指标端点暴露业务指标() throws Exception {
        // 先制造一次业务事件：提交报修（工单落库成功后才计数）
        String student = login(TENANT == 1L ? "gdou" : "gdou", "test-p5-student");
        long buildingId = 1L;
        mockMvc.perform(post("/api/student/tickets")
                        .header("Authorization", "Bearer " + student)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "idempotencyKey", "test-" + System.nanoTime(),
                                "buildingId", buildingId, "room", "1-101",
                                "categoryId", 1, "description", "指标测试工单", "urgency", 1))))
                .andExpect(jsonPath("$.code").value(0));

        String metrics = mockMvc.perform(get("/actuator/prometheus"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);

        assertThat(metrics).contains("repair_ticket_events_total");
        assertThat(metrics).contains("event=\"SUBMIT\"");
        // 指标名里的 application 标签来自 management.metrics.tags.application
        assertThat(metrics).contains("application=\"repair-platform\"");
    }

    // ==================== 工具 ====================

    private long studentCount() {
        return sysUserMapper.selectCount(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getTenantId, TENANT)
                .eq(SysUser::getUserType, UserType.STUDENT.getCode()));
    }

    private long workerCount() {
        return sysUserMapper.selectCount(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getTenantId, TENANT)
                .eq(SysUser::getUserType, UserType.WORKER.getCode()));
    }

    private void setQuota(int maxStudents, int maxWorkers) {
        tenantMapper.update(null, Wrappers.<Tenant>lambdaUpdate()
                .eq(Tenant::getId, TENANT)
                .set(Tenant::getMaxStudents, maxStudents)
                .set(Tenant::getMaxWorkers, maxWorkers));
        // 配额的读取不经过缓存（每次建号都读库），所以这里不需要清缓存
    }

    private Map<String, Object> studentBody(String username) {
        Map<String, Object> body = new HashMap<>();
        body.put("username", username);
        body.put("realName", "测试学生");
        body.put("password", PASSWORD);
        return body;
    }

    private JsonNode platformTenantRow() throws Exception {
        JsonNode page = json(mockMvc.perform(get("/api/platform/tenants")
                        .param("pageSize", "100")
                        .header("Authorization", "Bearer " + platform))
                .andExpect(jsonPath("$.code").value(0)).andReturn()).path("data");
        for (JsonNode row : page.path("list")) {
            if (row.path("id").asLong() == TENANT) {
                return row;
            }
        }
        throw new AssertionError("平台列表里没有租户 " + TENANT);
    }

    private String givenToken(long tenantId, String username, UserType type, long roleId) throws Exception {
        givenAccount(tenantId, username, type, roleId);
        return login(tenantId == 0L ? "platform" : "gdou", username);
    }

    private long givenAccount(long tenantId, String username, UserType type, long roleId) {
        SysUser user = new SysUser();
        user.setTenantId(tenantId);
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setRealName(username);
        user.setUserType(type.getCode());
        user.setStatus(1);
        sysUserMapper.insert(user);

        SysUserRole link = new SysUserRole();
        link.setUserId(user.getId());
        link.setRoleId(roleId);
        sysUserRoleMapper.insert(link);
        return user.getId();
    }

    private String login(String tenantCode, String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "tenantCode", tenantCode, "username", username, "password", PASSWORD))))
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        return json(result).path("data").path("tokenValue").asText();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
