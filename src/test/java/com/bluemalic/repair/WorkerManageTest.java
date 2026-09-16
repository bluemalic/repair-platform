package com.bluemalic.repair;

import com.bluemalic.repair.entity.Building;
import com.bluemalic.repair.entity.SysUser;
import com.bluemalic.repair.entity.SysUserRole;
import com.bluemalic.repair.mapper.BuildingMapper;
import com.bluemalic.repair.mapper.SysUserMapper;
import com.bluemalic.repair.mapper.SysUserRoleMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 维修工管理（docs/03 §5.4 基础数据接口的第一批）。
 *
 * <p>重点不是 CRUD 本身，而是三件容易只做一半的事：
 * <ol>
 *   <li><b>新增的维修工真的能用</b>——账号建了但角色关联漏插，表现是"能登录、每个接口都 403"，
 *       光看新增接口返回 0 是发现不了的</li>
 *   <li><b>设置负责楼栋真的改变数据范围</b>——这块数据是维修工可见范围的物理依据（ADR-002），
 *       "接口返回成功"和"他确实看得到/看不到"是两回事</li>
 *   <li><b>停用真的把人挡住</b>——登录态在 Redis，不随 {@code sys_user.status} 变化；
 *       只写库的话，他手上那个 7 天有效的 token 还能接着接单</li>
 * </ol>
 *
 * <p>种子数据依赖：租户 1（gdou）、角色 1学生/2维修工/3后勤管理、楼栋 1~5、
 * 报修码 482913 → 1号楼 1-101。测试自造的账号统一 {@code test-} 前缀，
 * 避免与 {@code docs/dev-seed.sql} 的演示账号撞 {@code uk_tenant_username}。
 */
@IntegrationTest
class WorkerManageTest {

    private static final String PASSWORD = "Test@123456";
    private static final String WORKER_PASSWORD = "Worker@123456";
    private static final long ROLE_STUDENT = 1L;
    private static final long ROLE_ADMIN = 3L;
    private static final long BUILDING_1 = 1L;
    private static final long BUILDING_2 = 2L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private SysUserRoleMapper sysUserRoleMapper;

    @Autowired
    private BuildingMapper buildingMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void createdWorkerCanLoginAndCarriesWorkerPermissions() throws Exception {
        String admin = givenToken("test-wm-admin", 3, ROLE_ADMIN);

        createWorker(admin, "test-wm-001", "李师傅");

        // 能登录只说明 sys_user 建对了；roles/permissions 非空才说明 sys_user_role 也插对了
        String workerToken = login("test-wm-001", WORKER_PASSWORD);
        JsonNode me = getJson(workerToken, "/api/auth/me");
        assertThat(me.path("code").asInt()).isZero();
        assertThat(me.path("data").path("roles").toString()).contains("WORKER");
        assertThat(me.path("data").path("permissions")).isNotEmpty();

        // 列表里查得到，负责楼栋为空数组（不是 null）
        JsonNode list = getJson(admin, "/api/admin/workers", "keyword", "test-wm-001");
        assertThat(list.path("data").path("total").asInt()).isEqualTo(1);
        assertThat(list.path("data").path("list").get(0).path("username").asText()).isEqualTo("test-wm-001");
        assertThat(list.path("data").path("list").get(0).path("buildingIds")).isEmpty();
    }

    @Test
    void createRejectsDuplicateUsername() throws Exception {
        String admin = givenToken("test-wm-dup-admin", 3, ROLE_ADMIN);
        createWorker(admin, "test-wm-dup", "李师傅");

        postJson(admin, "/api/admin/workers", Map.of(
                "username", "test-wm-dup", "realName", "另一个人", "password", WORKER_PASSWORD))
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.message").value("工号已存在"));
    }

    /** 本批最有价值的一条：负责楼栋是数据权限的物理依据，改它必须立刻改变可见范围。 */
    @Test
    void settingBuildingsChangesWhatTheWorkerCanSee() throws Exception {
        String admin = givenToken("test-wm-scope-admin", 3, ROLE_ADMIN);
        String student = givenToken("test-wm-scope-student", 1, ROLE_STUDENT);
        long workerId = createWorker(admin, "test-wm-scope", "王师傅");
        String workerToken = login("test-wm-scope", WORKER_PASSWORD);

        // 1 号楼（报修码 482913 = 1号楼 1-101）的一张待派单工单
        String ticketId = submitTicket(student);

        // 还没配负责楼栋 → 一条也看不到（拦截器注入 1=0 的既有语义）
        assertThat(ticketIds(workerToken)).isEmpty();

        // 设置负责 1 号楼 → 看得到
        setBuildings(admin, workerId, List.of(BUILDING_1)).andExpect(jsonPath("$.code").value(0));
        assertThat(ticketIds(workerToken)).contains(ticketId);

        // 改成只负责 2 号楼 → 又看不到了。这一步是关键：证明不是"加了一条就一直有"，而是每次按当前配置算
        setBuildings(admin, workerId, List.of(BUILDING_2)).andExpect(jsonPath("$.code").value(0));
        assertThat(ticketIds(workerToken)).doesNotContain(ticketId);
    }

    /** 跨租户的楼栋必须被拒，且**不能把师傅原有的授权清空**（校验先于删除）。 */
    @Test
    void foreignTenantBuildingIsRejectedWithoutClearingExistingAssignment() throws Exception {
        String admin = givenToken("test-wm-tenant-admin", 3, ROLE_ADMIN);
        long workerId = createWorker(admin, "test-wm-tenant", "赵师傅");
        setBuildings(admin, workerId, List.of(BUILDING_1)).andExpect(jsonPath("$.code").value(0));

        // 别家租户的楼栋（应用层保证租户隔离，不建物理外键，所以这里直接插一条租户 2 的楼栋）
        Building foreign = new Building();
        foreign.setTenantId(2L);
        foreign.setName("别家的楼");
        foreign.setSort(0);
        foreign.setStatus(1);
        buildingMapper.insert(foreign);

        setBuildings(admin, workerId, List.of(BUILDING_1, foreign.getId()))
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.message").value("楼栋不存在或不属于本租户"));

        // 原来的 1 号楼授权必须还在——如果实现是先删后校验，这里会变成空数组
        JsonNode list = getJson(admin, "/api/admin/workers", "keyword", "test-wm-tenant");
        assertThat(list.path("data").path("list").get(0).path("buildingIds").toString()).contains("1");
    }

    @Test
    void disablingWorkerKicksSessionAndBlocksDispatch() throws Exception {
        String admin = givenToken("test-wm-disable-admin", 3, ROLE_ADMIN);
        String student = givenToken("test-wm-disable-student", 1, ROLE_STUDENT);
        long workerId = createWorker(admin, "test-wm-disable", "钱师傅");
        String workerToken = login("test-wm-disable", WORKER_PASSWORD);

        // 停用前登录态有效
        getJsonRaw(workerToken, "/api/auth/me").andExpect(status().isOk());

        putJson(admin, "/api/admin/workers/" + workerId, Map.of(
                "realName", "钱师傅", "status", 0)).andExpect(jsonPath("$.code").value(0));

        // 停用后旧 token 立刻失效：登录态在 Redis，光改 sys_user.status 是挡不住的
        getJsonRaw(workerToken, "/api/auth/me")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(10002));

        // 派单也拒（dispatch 里已有的"必须是启用的维修工"校验）
        String ticketId = submitTicket(student);
        postJson(admin, "/api/admin/tickets/" + ticketId + "/dispatch",
                Map.of("workerId", workerId))
                .andExpect(jsonPath("$.code").value(10001));
    }

    @Test
    void studentCannotManageWorkers() throws Exception {
        String student = givenToken("test-wm-forbidden", 1, ROLE_STUDENT);

        getJsonRaw(student, "/api/admin/workers")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(10003));
    }

    @Test
    void pageFiltersByStatusAndKeyword() throws Exception {
        String admin = givenToken("test-wm-page-admin", 3, ROLE_ADMIN);
        createWorker(admin, "test-wm-page-a", "张三");
        long disabledId = createWorker(admin, "test-wm-page-b", "李四");
        putJson(admin, "/api/admin/workers/" + disabledId, Map.of(
                "realName", "李四", "status", 0)).andExpect(jsonPath("$.code").value(0));

        // 关键字命中工号或姓名
        JsonNode byPrefix = getJson(admin, "/api/admin/workers", "keyword", "test-wm-page");
        assertThat(byPrefix.path("data").path("total").asInt()).isEqualTo(2);

        JsonNode byName = getJson(admin, "/api/admin/workers", "keyword", "张三");
        assertThat(byName.path("data").path("list").get(0).path("username").asText())
                .isEqualTo("test-wm-page-a");

        // 状态筛选
        JsonNode disabled = getJson(admin, "/api/admin/workers", "keyword", "test-wm-page", "status", "0");
        assertThat(disabled.path("data").path("total").asInt()).isEqualTo(1);
        assertThat(disabled.path("data").path("list").get(0).path("username").asText())
                .isEqualTo("test-wm-page-b");
    }

    // ==================== 工具 ====================

    private long createWorker(String admin, String username, String realName) throws Exception {
        MvcResult result = postJson(admin, "/api/admin/workers", Map.of(
                "username", username, "realName", realName, "phone", "13800000000",
                "password", WORKER_PASSWORD))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        return readJson(result).path("data").path("id").asLong();
    }

    private ResultActions setBuildings(String admin, long workerId, List<Long> buildingIds) throws Exception {
        return putJson(admin, "/api/admin/workers/" + workerId + "/buildings",
                Map.of("buildingIds", buildingIds));
    }

    /** 学生用种子报修码提交一张 1 号楼的工单，返回工单 ID。 */
    private String submitTicket(String student) throws Exception {
        MvcResult result = postJson(student, "/api/student/tickets", Map.of(
                "repairCode", "482913", "categoryId", 1, "description", "维修工管理测试"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        return readJson(result).path("data").path("id").asText();
    }

    /** 维修工"我的派单"里的工单 ID 列表。可见范围由数据权限拦截器按负责楼栋注入。 */
    private List<String> ticketIds(String workerToken) throws Exception {
        JsonNode list = getJson(workerToken, "/api/worker/tickets").path("data").path("list");
        return StreamSupport.stream(list.spliterator(), false)
                .map(node -> node.path("id").asText())
                .toList();
    }

    private ResultActions postJson(String token, String url, Map<String, Object> body) throws Exception {
        return mockMvc.perform(post(url)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private ResultActions putJson(String token, String url, Map<String, Object> body) throws Exception {
        return mockMvc.perform(put(url)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private JsonNode getJson(String token, String url) throws Exception {
        return getJson(token, get(url));
    }

    private JsonNode getJson(String token, String url, String... paramPairs) throws Exception {
        MockHttpServletRequestBuilder request = get(url);
        for (int i = 0; i + 1 < paramPairs.length; i += 2) {
            request.param(paramPairs[i], paramPairs[i + 1]);
        }
        return getJson(token, request);
    }

    private JsonNode getJson(String token, MockHttpServletRequestBuilder request) throws Exception {
        MvcResult result = mockMvc.perform((RequestBuilder) request
                        .header("Authorization", "Bearer " + token))
                .andReturn();
        return readJson(result);
    }

    private ResultActions getJsonRaw(String token, String url) throws Exception {
        return mockMvc.perform(get(url).header("Authorization", "Bearer " + token));
    }

    private JsonNode readJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** 造用户 + 角色并登录拿 token（租户 1 = 种子数据 gdou）。 */
    private String givenToken(String username, int userType, long roleId) throws Exception {
        SysUser user = new SysUser();
        user.setTenantId(1L);
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setRealName(username);
        user.setUserType(userType);
        user.setStatus(1);
        sysUserMapper.insert(user);

        SysUserRole link = new SysUserRole();
        link.setUserId(user.getId());
        link.setRoleId(roleId);
        sysUserRoleMapper.insert(link);

        return login(username, PASSWORD);
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "tenantCode", "gdou", "username", username, "password", password))))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        return readJson(result).path("data").path("tokenValue").asText();
    }
}
