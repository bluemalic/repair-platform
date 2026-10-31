package com.bluemalic.repair;

import com.bluemalic.repair.entity.SysUser;
import com.bluemalic.repair.entity.SysUserRole;
import com.bluemalic.repair.mapper.SysUserMapper;
import com.bluemalic.repair.mapper.SysUserRoleMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 设备台账（阶段 3 · docs/01 §4.8）。重点在三条边界：
 * <ul>
 *   <li>设备编号租户内唯一（新增与改名都要拦）；</li>
 *   <li>被工单引用的设备不能删除，只能停用/报废；</li>
 *   <li>租户隔离：别的租户的设备查不到（asset 不在数据权限拦截器范围内，靠 Service 显式带租户条件）。</li>
 * </ul>
 *
 * <p>种子依赖：租户 1（gdou）、楼栋 1（1号楼）、类别 1（水电）、角色 3 后勤管理。
 */
@IntegrationTest
@AutoConfigureMockMvc
class AssetManageTest {

    private static final String PASSWORD = "Test@123456";
    private static final long ROLE_ADMIN = 3L;
    private static final long SEED_BUILDING = 1L;
    private static final long SEED_CATEGORY = 1L;
    private static final long SEED_TENANT = 1L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private SysUserRoleMapper sysUserRoleMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void createListAndDetail() throws Exception {
        String admin = givenToken("test-asset-admin");

        long buildingId = createBuilding(admin, "资产测试楼");
        long categoryId = createCategory(admin, "资产测试类别");
        long id = createAsset(admin, "AC-T-001", "测试空调", buildingId, categoryId);

        JsonNode page = getJson(admin, "/api/admin/assets", "keyword", "AC-T-001");
        assertThat(assetNosOf(page)).contains("AC-T-001");
        JsonNode first = page.path("data").path("list").get(0);
        assertThat(first.path("buildingName").asText()).isEqualTo("资产测试楼");
        assertThat(first.path("categoryName").asText()).isEqualTo("资产测试类别");

        JsonNode detail = getJson(admin, "/api/admin/assets/" + id);
        assertThat(detail.path("data").path("assetNo").asText()).isEqualTo("AC-T-001");
    }

    @Test
    void assetNoMustBeUniqueWithinTenant() throws Exception {
        String admin = givenToken("test-asset-unique");
        createAsset(admin, "AC-U-001", "唯一设备");

        postJson(admin, "/api/admin/assets", assetBody("AC-U-001", "另一台"))
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.message").value("设备编号已存在：AC-U-001"));
    }

    @Test
    void updateStatusAndFilterDisabled() throws Exception {
        String admin = givenToken("test-asset-update");
        long id = createAsset(admin, "AC-S-001", "报废设备");

        putJson(admin, "/api/admin/assets/" + id, Map.of(
                "assetNo", "AC-S-001", "name", "报废设备", "status", 3,
                "buildingId", SEED_BUILDING, "categoryId", SEED_CATEGORY, "room", "201"))
                .andExpect(jsonPath("$.code").value(0));

        JsonNode disabled = getJson(admin, "/api/admin/assets", "status", "3", "keyword", "AC-S-001");
        assertThat(assetNosOf(disabled)).contains("AC-S-001");
    }

    @Test
    void deleteUnreferencedAsset() throws Exception {
        String admin = givenToken("test-asset-delete");
        long id = createAsset(admin, "AC-D-001", "可删设备");

        deleteJson(admin, "/api/admin/assets/" + id).andExpect(jsonPath("$.code").value(0));

getJsonRaw(admin, "/api/admin/assets/" + id).andExpect(jsonPath("$.code").value(10006));
    }

    @Test
    void deleteBlockedWhenAssetHasTickets() throws Exception {
        String admin = givenToken("test-asset-referenced");
        long assetId = createAsset(admin, "AC-R-001", "有工单的设备");

        // 直接插一张引用该设备的工单：本 PR 还没把 assetId 接到学生提交接口上，
        // 这里验证的是删除守卫本身（下一 PR 接提交链路时再补端到端用例）。
        jdbcTemplate.update("""
                INSERT INTO ticket (id, tenant_id, ticket_no, student_id, building_id, asset_id, room,
                                    category_id, description, urgency, status, submit_time,
                                    deleted, create_time, update_time)
                VALUES (?, ?, ?, 1, ?, ?, '201', ?, '设备台账测试', 1, 20, NOW(), 0, NOW(), NOW())
                """, System.nanoTime(), SEED_TENANT, "T-ASSET-" + System.nanoTime(),
                SEED_BUILDING, assetId, SEED_CATEGORY);

        deleteJson(admin, "/api/admin/assets/" + assetId)
                .andExpect(jsonPath("$.code").value(10001));

        // 删除被拒时设备必须还在
getJsonRaw(admin, "/api/admin/assets/" + assetId).andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void foreignBuildingOrCategoryIsRejected() throws Exception {
        String admin = givenToken("test-asset-ref");

        long foreignBuilding = System.nanoTime();
        jdbcTemplate.update("""
                INSERT INTO building (id, tenant_id, name, sort, status, deleted, create_time, update_time)
                VALUES (?, 2, '别的学校的楼', 0, 1, 0, NOW(), NOW())
                """, foreignBuilding);
        postJson(admin, "/api/admin/assets", Map.of(
                "assetNo", "AC-F-001", "name", "引用外校楼栋",
                "buildingId", foreignBuilding))
                .andExpect(jsonPath("$.code").value(10001));

        long foreignCategory = System.nanoTime();
        jdbcTemplate.update("""
                INSERT INTO ticket_category (id, tenant_id, name, default_urgency, sort, status, deleted,
                                             create_time, update_time)
                VALUES (?, 2, '别的学校的类别', 1, 0, 1, 0, NOW(), NOW())
                """, foreignCategory);
        postJson(admin, "/api/admin/assets", Map.of(
                "assetNo", "AC-F-002", "name", "引用外校类别",
                "categoryId", foreignCategory))
                .andExpect(jsonPath("$.code").value(10001));
    }

    @Test
    void assetOfAnotherTenantIsInvisible() throws Exception {
        String admin = givenToken("test-asset-isolation");
        long foreignId = System.nanoTime();
        jdbcTemplate.update("""
                INSERT INTO asset (id, tenant_id, asset_no, name, status, deleted, create_time, update_time)
                VALUES (?, 2, 'AC-X-001', '别的学校的设备', 1, 0, NOW(), NOW())
                """, foreignId);

getJsonRaw(admin, "/api/admin/assets/" + foreignId).andExpect(jsonPath("$.code").value(10006));
    }

    private long createAsset(String admin, String assetNo, String name) throws Exception {
        return createAsset(admin, assetNo, name, SEED_BUILDING, SEED_CATEGORY);
    }

    private long createAsset(String admin, String assetNo, String name, long buildingId, long categoryId)
            throws Exception {
        Map<String, Object> body = Map.of(
                "assetNo", assetNo, "name", name,
                "buildingId", buildingId, "categoryId", categoryId,
                "room", "201", "supplier", "测试供应商");
        MvcResult result = postJson(admin, "/api/admin/assets", body)
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        return readJson(result).path("data").path("id").asLong();
    }

    private long createBuilding(String admin, String name) throws Exception {
        MvcResult result = postJson(admin, "/api/admin/buildings", Map.of("name", name, "sort", 0))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        return readJson(result).path("data").path("id").asLong();
    }

    private long createCategory(String admin, String name) throws Exception {
        MvcResult result = postJson(admin, "/api/admin/categories",
                Map.of("name", name, "defaultUrgency", 1, "sort", 0))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        return readJson(result).path("data").path("id").asLong();
    }

    private Map<String, Object> assetBody(String assetNo, String name) {
        return Map.of(
                "assetNo", assetNo,
                "name", name,
                "buildingId", SEED_BUILDING,
                "categoryId", SEED_CATEGORY,
                "room", "201",
                "supplier", "测试供应商");
    }

    private java.util.List<String> assetNosOf(JsonNode pageResult) {
        return StreamSupport.stream(pageResult.path("data").path("list").spliterator(), false)
                .map(node -> node.path("assetNo").asText())
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

    /** 只断言、不解析体的 GET（错误码断言用这个）。 */
    private ResultActions getJsonRaw(String token, String url) throws Exception {
        return mockMvc.perform(get(url).header("Authorization", "Bearer " + token));
    }

    private JsonNode getJson(String token, String url, String... paramPairs) throws Exception {
        MockHttpServletRequestBuilder request = get(url);
        for (int i = 0; i + 1 < paramPairs.length; i += 2) {
            request.param(paramPairs[i], paramPairs[i + 1]);
        }
        return readJson(mockMvc.perform(request
                .header("Authorization", "Bearer " + token)).andReturn());
    }

    private JsonNode readJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private String givenToken(String username) throws Exception {
        SysUser user = new SysUser();
        user.setTenantId(SEED_TENANT);
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setRealName(username);
        user.setUserType(3);
        user.setStatus(1);
        sysUserMapper.insert(user);

        SysUserRole link = new SysUserRole();
        link.setUserId(user.getId());
        link.setRoleId(ROLE_ADMIN);
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
