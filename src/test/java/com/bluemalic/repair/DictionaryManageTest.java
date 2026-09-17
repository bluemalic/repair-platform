package com.bluemalic.repair;

import com.bluemalic.repair.entity.Building;
import com.bluemalic.repair.entity.RepairCode;
import com.bluemalic.repair.entity.SysUser;
import com.bluemalic.repair.entity.SysUserRole;
import com.bluemalic.repair.mapper.BuildingMapper;
import com.bluemalic.repair.mapper.RepairCodeMapper;
import com.bluemalic.repair.mapper.SysUserMapper;
import com.bluemalic.repair.mapper.SysUserRoleMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 楼栋 / 类别字典管理（docs/03 §5.4 基础数据接口第二批）。
 *
 * <p>重点在**删除的边界**，因为这两张表有 DELETE，而删除在字典数据里最危险：
 * MyBatis-Plus 的全局逻辑删除会把软删行从所有查询里滤掉，所以"删掉一个被引用的楼栋"不会
 * 报错，只会让引用它的地方**静默显示空名称**——历史工单的楼栋名、学生扫码拿到的楼栋名、
 * 师傅可见范围里挂着的楼栋。所以规则是：**用过的只能停用，删除只留给建错且从没被用过的**。
 *
 * <p>种子数据依赖：租户 1（gdou）、角色 1学生/3后勤管理、楼栋 1~5、类别 1~6、
 * 报修码 482913 → 1号楼 1-101。
 */
@IntegrationTest
class DictionaryManageTest {

    private static final String PASSWORD = "Test@123456";
    private static final long ROLE_STUDENT = 1L;
    private static final long ROLE_ADMIN = 3L;
    private static final long SEED_CATEGORY = 1L;

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
    private RepairCodeMapper repairCodeMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void buildingCreateListAndUpdate() throws Exception {
        String admin = givenToken("test-dict-admin", 3, ROLE_ADMIN);

        long buildingId = createBuilding(admin, "测试楼", "东区", 7);

        // 列表按 sort 升序：新楼栋 sort=7，种子的 1~5 号楼 sort 更小，所以它排在后面
        JsonNode list = getJson(admin, "/api/admin/buildings");
        assertThat(namesOf(list, "name")).endsWith("测试楼");

        // PUT 是提交最终状态：改名 + 停用
        putJson(admin, "/api/admin/buildings/" + buildingId, Map.of(
                "name", "测试楼（改名）", "area", "西区", "sort", 1, "status", 0))
                .andExpect(jsonPath("$.code").value(0));

        JsonNode disabled = getJson(admin, "/api/admin/buildings", "status", "0");
        assertThat(namesOf(disabled, "name")).contains("测试楼（改名）");
        assertThat(namesOf(getJson(admin, "/api/admin/buildings"), "name")).contains("测试楼（改名）");
    }

    @Test
    void buildingNameMustBeUniqueWithinTenant() throws Exception {
        String admin = givenToken("test-dict-unique", 3, ROLE_ADMIN);

        long id = createBuilding(admin, "唯一楼", null, 0);
        // 用自己造的名字验重，不借种子的名字——种子里那些名字管理员可以在管理端改掉
        postJson(admin, "/api/admin/buildings", Map.of("name", "唯一楼"))
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.message").value("楼栋名称已存在"));
        long other = createBuilding(admin, "另一栋", null, 0);

        // 改名撞上已有的名字也要拦
        putJson(admin, "/api/admin/buildings/" + other, Map.of(
                "name", "唯一楼", "sort", 0, "status", 1))
                .andExpect(jsonPath("$.code").value(10001));
        // 改成自己原来的名字不算重复（排除自身）
        putJson(admin, "/api/admin/buildings/" + id, Map.of(
                "name", "唯一楼", "sort", 0, "status", 1))
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void deleteBlockedWhenBuildingHasTickets() throws Exception {
        String admin = givenToken("test-dict-ticket-admin", 3, ROLE_ADMIN);
        String student = givenToken("test-dict-ticket-student", 1, ROLE_STUDENT);
        long buildingId = createBuilding(admin, "有工单的楼", null, 0);
        submitTicket(student, buildingId, SEED_CATEGORY);

        deleteJson(admin, "/api/admin/buildings/" + buildingId)
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.message").value("该楼栋下已有工单，不能删除；下线请改为停用"));

        // 楼栋必须还在（删除被拒时不能把数据改坏）
        assertThat(namesOf(getJson(admin, "/api/admin/buildings"), "name")).contains("有工单的楼");
    }

    @Test
    void deleteBlockedWhenWorkerIsAssigned() throws Exception {
        String admin = givenToken("test-dict-worker-admin", 3, ROLE_ADMIN);
        long buildingId = createBuilding(admin, "有师傅的楼", null, 0);

        long workerId = createWorker(admin, "test-dict-worker");
        putJson(admin, "/api/admin/workers/" + workerId + "/buildings",
                Map.of("buildingIds", List.of(buildingId)))
                .andExpect(jsonPath("$.code").value(0));

        deleteJson(admin, "/api/admin/buildings/" + buildingId)
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.message").value("还有维修工负责该楼栋，请先在维修工管理里解除"));
    }

    @Test
    void deleteBlockedWhenBuildingHasRepairCodes() throws Exception {
        String admin = givenToken("test-dict-code-admin", 3, ROLE_ADMIN);
        long buildingId = createBuilding(admin, "有报修码的楼", null, 0);

        RepairCode code = new RepairCode();
        code.setTenantId(1L);
        code.setCode("998877");
        code.setBuildingId(buildingId);
        code.setRoom("9-101");
        code.setStatus(1);
        repairCodeMapper.insert(code);

        deleteJson(admin, "/api/admin/buildings/" + buildingId)
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.message").value("该楼栋下还有报修码，请先处理报修码"));
    }

    /** 没被引用过的楼栋可以删，且是**逻辑删除**：接口查不到了，行还在库里（带 deleted=1）。 */
    @Test
    void unreferencedBuildingIsLogicallyDeleted() throws Exception {
        String admin = givenToken("test-dict-del-admin", 3, ROLE_ADMIN);
        long buildingId = createBuilding(admin, "建错的楼", null, 0);

        deleteJson(admin, "/api/admin/buildings/" + buildingId).andExpect(jsonPath("$.code").value(0));

        assertThat(namesOf(getJson(admin, "/api/admin/buildings"), "name")).doesNotContain("建错的楼");
        assertThat(buildingMapper.selectById(buildingId)).isNull();

        // 行仍在、deleted=1：这正是"逻辑删除"与"物理删除"的区别，也是它能被事后查证的原因
        Integer deleted = jdbcTemplate.queryForObject(
                "SELECT deleted FROM building WHERE id = ?", Integer.class, buildingId);
        assertThat(deleted).isEqualTo(1);
    }

    @Test
    void foreignTenantBuildingIsTreatedAsMissing() throws Exception {
        String admin = givenToken("test-dict-tenant-admin", 3, ROLE_ADMIN);

        Building foreign = new Building();
        foreign.setTenantId(2L);
        foreign.setName("别家的楼");
        foreign.setSort(0);
        foreign.setStatus(1);
        buildingMapper.insert(foreign);

        putJson(admin, "/api/admin/buildings/" + foreign.getId(), Map.of(
                "name", "改名试试", "sort", 0, "status", 1))
                .andExpect(jsonPath("$.code").value(10006));
        deleteJson(admin, "/api/admin/buildings/" + foreign.getId())
                .andExpect(jsonPath("$.code").value(10006));
    }

    @Test
    void categoryCrudAndDisableBlocksNewTickets() throws Exception {
        String admin = givenToken("test-dict-cat-admin", 3, ROLE_ADMIN);
        String student = givenToken("test-dict-cat-student", 1, ROLE_STUDENT);

        long categoryId = createCategory(admin, "测试类别", 2, 0);
        postJson(admin, "/api/admin/categories", Map.of("name", "测试类别"))
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.message").value("类别名称已存在"));

        // 启用的类别能用于新报修
        submitTicket(student, 1L, categoryId);

        // 被工单引用过 → 不能删，只能停用
        deleteJson(admin, "/api/admin/categories/" + categoryId)
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.message").value("该类别下已有工单，不能删除；下线请改为停用"));

        // 停用后不能再用于新报修（submit 里的既有校验：存在 + 同租户 + 启用）
        putJson(admin, "/api/admin/categories/" + categoryId, Map.of(
                "name", "测试类别", "defaultUrgency", 2, "sort", 0, "status", 0))
                .andExpect(jsonPath("$.code").value(0));
        postJson(student, "/api/student/tickets", Map.of(
                "repairCode", "482913", "categoryId", categoryId, "description", "停用类别"))
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.message").value("报修类别不存在或已停用"));

        // 没被引用过的新类别可以删
        long spare = createCategory(admin, "没人用的类别", 1, 0);
        deleteJson(admin, "/api/admin/categories/" + spare).andExpect(jsonPath("$.code").value(0));
        assertThat(namesOf(getJson(admin, "/api/admin/categories"), "name")).doesNotContain("没人用的类别");
    }

    @Test
    void studentCannotManageDictionaries() throws Exception {
        String student = givenToken("test-dict-forbidden", 1, ROLE_STUDENT);

        getJsonRaw(student, "/api/admin/buildings")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(10003));
        getJsonRaw(student, "/api/admin/categories")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(10003));
    }

    // ==================== 工具 ====================

    private long createBuilding(String admin, String name, String area, int sort) throws Exception {
        Map<String, Object> body = area == null
                ? Map.of("name", name, "sort", sort)
                : Map.of("name", name, "area", area, "sort", sort);
        MvcResult result = postJson(admin, "/api/admin/buildings", body)
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        return readJson(result).path("data").path("id").asLong();
    }

    private long createCategory(String admin, String name, int defaultUrgency, int sort) throws Exception {
        MvcResult result = postJson(admin, "/api/admin/categories", Map.of(
                "name", name, "defaultUrgency", defaultUrgency, "sort", sort))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        return readJson(result).path("data").path("id").asLong();
    }

    private long createWorker(String admin, String username) throws Exception {
        MvcResult result = postJson(admin, "/api/admin/workers", Map.of(
                "username", username, "realName", "测试师傅", "password", "Worker@123456"))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        return readJson(result).path("data").path("id").asLong();
    }

    private void submitTicket(String student, long buildingId, long categoryId) throws Exception {
        postJson(student, "/api/student/tickets", Map.of(
                "buildingId", buildingId, "room", "9-101",
                "categoryId", categoryId, "description", "字典管理测试"))
                .andExpect(jsonPath("$.code").value(0));
    }

    private List<String> namesOf(JsonNode listResult, String field) {
        JsonNode array = listResult.path("data");
        return StreamSupport.stream(array.spliterator(), false)
                .map(node -> node.path(field).asText())
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

    private ResultActions deleteJson(String token, String url) throws Exception {
        return mockMvc.perform(delete(url).header("Authorization", "Bearer " + token));
    }

    private JsonNode getJson(String token, String url, String... paramPairs) throws Exception {
        MockHttpServletRequestBuilder request = get(url);
        for (int i = 0; i + 1 < paramPairs.length; i += 2) {
            request.param(paramPairs[i], paramPairs[i + 1]);
        }
        return readJson(mockMvc.perform((RequestBuilder) request
                .header("Authorization", "Bearer " + token)).andReturn());
    }

    private ResultActions getJsonRaw(String token, String url) throws Exception {
        return mockMvc.perform(get(url).header("Authorization", "Bearer " + token));
    }

    private JsonNode readJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

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

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "tenantCode", "gdou", "username", username, "password", PASSWORD))))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        return readJson(result).path("data").path("tokenValue").asText();
    }
}
