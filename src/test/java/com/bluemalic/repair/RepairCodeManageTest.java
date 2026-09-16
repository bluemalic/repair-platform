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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.RequestBuilder;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 报修码管理（docs/03 §5.4 基础数据接口第三批，也是最后一批）。
 *
 * <p>码是"位置码"（ADR-004），所以这批的重点不是 CRUD，而是三条不变量：
 *
 * <ol>
 *   <li><b>码是随机且不重复的</b>：旧文档写的"防枚举"要靠它——顺序码 / 可预测的码等于泄露房间清单</li>
 *   <li><b>一房一码</b>：数据库故意不建 {@code (building_id, room)} 唯一索引（软删后要能重建），
 *       这条不变量只能由服务层守</li>
 *   <li><b>生成的码当场可用</b>：学生扫码拿到的就是这条记录——这条端到端断言比任何字段断言都有价值</li>
 * </ol>
 *
 * <p>种子数据依赖：租户 1（gdou）、角色 1学生/3后勤管理、楼栋 1~5、
 * 种子报修码 482913 → 1号楼 1-101（所以本测试用别的房间号，避免撞"一房一码"）。
 */
@IntegrationTest
class RepairCodeManageTest {

    private static final String PASSWORD = "Test@123456";
    private static final long ROLE_STUDENT = 1L;
    private static final long ROLE_ADMIN = 3L;
    private static final long BUILDING_1 = 1L;
    private static final long BUILDING_2 = 2L;
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
    private RepairCodeMapper repairCodeMapper;

    @Autowired
    private BuildingMapper buildingMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void generatedCodeIsSixDigitsAndWorksRightAway() throws Exception {
        String admin = givenToken("test-code-admin", 3, ROLE_ADMIN);
        String student = givenToken("test-code-student", 1, ROLE_STUDENT);

        JsonNode created = createCode(admin, BUILDING_1, "1-201");
        String code = created.path("code").asText();

        // 6 位数字，且不以 0 开头（手输时前导零最容易漏）
        assertThat(code).matches("[1-9]\\d{5}");
        assertThat(created.path("buildingName").asText()).isEqualTo("1号楼");
        assertThat(created.path("status").asInt()).isEqualTo(1);

        // 端到端：这条新码立刻能被扫码接口查到，位置正确
        JsonNode byCode = getJson(student, "/api/tickets/by-code/" + code);
        assertThat(byCode.path("code").asInt()).isZero();
        assertThat(byCode.path("data").path("buildingName").asText()).isEqualTo("1号楼");
        assertThat(byCode.path("data").path("room").asText()).isEqualTo("1-201");

        // 也能直接用它提交报修（扫码报修的完整闭环）
        postJson(student, "/api/student/tickets", Map.of(
                "repairCode", code, "categoryId", SEED_CATEGORY, "description", "新码报修"))
                .andExpect(jsonPath("$.code").value(0));
    }

    @Test
    void codesAreDistinctAcrossRooms() throws Exception {
        String admin = givenToken("test-code-random-admin", 3, ROLE_ADMIN);

        Set<String> codes = new HashSet<>();
        for (int i = 1; i <= 8; i++) {
            codes.add(createCode(admin, BUILDING_1, "1-3" + i).path("code").asText());
        }
        // 8 次生成必须互不相同（码空间 90 万，撞码会被 nextCode 换掉）
        assertThat(codes).hasSize(8);
    }

    @Test
    void oneRoomKeepsOnlyOneCode() throws Exception {
        String admin = givenToken("test-code-room-admin", 3, ROLE_ADMIN);
        long codeId = createCode(admin, BUILDING_1, "1-401").path("id").asLong();

        // 同房间再建一条 → 拦住，并告诉调用方正确做法
        postJson(admin, "/api/admin/repair-codes", Map.of("buildingId", BUILDING_1, "room", "1-401"))
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.message").value("该房间已有报修码；换码请在原码上重新生成，不要新建"));

        // 停用后仍然算占用：否则同一房间留下两行，一房一码名存实亡
        putJson(admin, "/api/admin/repair-codes/" + codeId, Map.of(
                "room", "1-401", "status", 0)).andExpect(jsonPath("$.code").value(0));
        postJson(admin, "/api/admin/repair-codes", Map.of("buildingId", BUILDING_1, "room", "1-401"))
                .andExpect(jsonPath("$.code").value(10001));

        // 别的房间不受影响
        createCode(admin, BUILDING_1, "1-402");
    }

    @Test
    void createRejectsMissingOrForeignBuilding() throws Exception {
        String admin = givenToken("test-code-building-admin", 3, ROLE_ADMIN);

        postJson(admin, "/api/admin/repair-codes", Map.of("buildingId", 999999L, "room", "9-101"))
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.message").value("楼栋不存在或不属于本租户"));

        // 别家租户的楼栋（应用层保证租户隔离，不建物理外键，所以直接插一条租户 2 的楼栋）
        long foreignBuildingId = givenForeignBuilding();
        postJson(admin, "/api/admin/repair-codes", Map.of("buildingId", foreignBuildingId, "room", "9-102"))
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.message").value("楼栋不存在或不属于本租户"));
    }

    @Test
    void updateRoomStatusAndRegenerate() throws Exception {
        String admin = givenToken("test-code-update-admin", 3, ROLE_ADMIN);
        String student = givenToken("test-code-update-student", 1, ROLE_STUDENT);
        JsonNode created = createCode(admin, BUILDING_1, "1-501");
        long codeId = created.path("id").asLong();
        String oldCode = created.path("code").asText();

        // 改房间号：位置跟着变
        putJson(admin, "/api/admin/repair-codes/" + codeId, Map.of(
                "room", "1-502", "status", 1)).andExpect(jsonPath("$.code").value(0));
        assertThat(getJson(student, "/api/tickets/by-code/" + oldCode)
                .path("data").path("room").asText()).isEqualTo("1-502");

        // 改成已被占用的房间 → 拦住（可用房间用种子码 482913 的 1-101）
        putJson(admin, "/api/admin/repair-codes/" + codeId, Map.of(
                "room", "1-101", "status", 1))
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.message").value("该房间已有报修码；换码请在原码上重新生成，不要新建"));

        // 停用：扫码立刻失效（20006）
        putJson(admin, "/api/admin/repair-codes/" + codeId, Map.of(
                "room", "1-502", "status", 0)).andExpect(jsonPath("$.code").value(0));
        getJsonRaw(student, "/api/tickets/by-code/" + oldCode)
                .andExpect(jsonPath("$.code").value(20006));

        // 重新生成：码变了、旧码失效、新码可用，房间不变
        putJson(admin, "/api/admin/repair-codes/" + codeId, Map.of(
                "room", "1-502", "status", 1, "regenerate", true))
                .andExpect(jsonPath("$.code").value(0));
        JsonNode listed = getJson(admin, "/api/admin/repair-codes", "buildingId", String.valueOf(BUILDING_1));
        String newCode = codeOfRoom(listed, "1-502");
        assertThat(newCode).matches("[1-9]\\d{5}").isNotEqualTo(oldCode);
        getJsonRaw(student, "/api/tickets/by-code/" + oldCode)
                .andExpect(jsonPath("$.code").value(20006));
        assertThat(getJson(student, "/api/tickets/by-code/" + newCode)
                .path("data").path("room").asText()).isEqualTo("1-502");
    }

    @Test
    void pageFiltersByBuildingAndStatus() throws Exception {
        String admin = givenToken("test-code-page-admin", 3, ROLE_ADMIN);
        createCode(admin, BUILDING_1, "1-601");
        long stoppedId = createCode(admin, BUILDING_2, "2-601").path("id").asLong();
        putJson(admin, "/api/admin/repair-codes/" + stoppedId, Map.of(
                "room", "2-601", "status", 0)).andExpect(jsonPath("$.code").value(0));

        // 按楼栋筛：2 号楼在种子里有一条码（751204），加上刚建的共 2 条
        JsonNode byBuilding = getJson(admin, "/api/admin/repair-codes",
                "buildingId", String.valueOf(BUILDING_2));
        assertThat(byBuilding.path("data").path("total").asInt()).isEqualTo(2);
        assertThat(namesOf(byBuilding, "buildingName")).containsOnly("2号楼");

        // 按状态筛：2 号楼那条已停用
        JsonNode stopped = getJson(admin, "/api/admin/repair-codes",
                "buildingId", String.valueOf(BUILDING_2), "status", "0");
        assertThat(stopped.path("data").path("total").asInt()).isEqualTo(1);
        assertThat(stopped.path("data").path("list").get(0).path("room").asText()).isEqualTo("2-601");

        // 分页字段齐全（docs/03 §2.1：total/pageNum/pageSize/pages/list）
        assertThat(byBuilding.path("data").path("pageSize").asInt()).isPositive();
        assertThat(byBuilding.path("data").path("list").isArray()).isTrue();
    }

    @Test
    void foreignTenantCodeIsTreatedAsMissing() throws Exception {
        String admin = givenToken("test-code-tenant-admin", 3, ROLE_ADMIN);

        RepairCode foreign = new RepairCode();
        foreign.setTenantId(2L);
        foreign.setCode("777777");
        foreign.setBuildingId(1L);
        foreign.setRoom("9-901");
        foreign.setStatus(1);
        repairCodeMapper.insert(foreign);

        putJson(admin, "/api/admin/repair-codes/" + foreign.getId(), Map.of(
                "room", "9-902", "status", 1))
                .andExpect(jsonPath("$.code").value(10006))
                .andExpect(jsonPath("$.message").value("报修码不存在"));
    }

    @Test
    void studentCannotManageRepairCodes() throws Exception {
        String student = givenToken("test-code-forbidden", 1, ROLE_STUDENT);

        getJsonRaw(student, "/api/admin/repair-codes")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(10003));
    }

    // ==================== 工具 ====================

    private JsonNode createCode(String admin, long buildingId, String room) throws Exception {
        MvcResult result = postJson(admin, "/api/admin/repair-codes", Map.of(
                "buildingId", buildingId, "room", room))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        return readJson(result).path("data");
    }

    /** 从列表结果里取某个房间的码。 */
    private String codeOfRoom(JsonNode pageResult, String room) {
        return StreamSupport.stream(pageResult.path("data").path("list").spliterator(), false)
                .filter(node -> room.equals(node.path("room").asText()))
                .map(node -> node.path("code").asText())
                .findFirst()
                .orElseThrow(() -> new AssertionError("列表里没有房间 " + room));
    }

    private List<String> namesOf(JsonNode pageResult, String field) {
        List<String> values = new ArrayList<>();
        StreamSupport.stream(pageResult.path("data").path("list").spliterator(), false)
                .forEach(node -> values.add(node.path(field).asText()));
        return values;
    }

    /** 造一栋属于租户 2 的楼，用于验证跨租户隔离。 */
    private long givenForeignBuilding() {
        Building building = new Building();
        building.setTenantId(2L);
        building.setName("别家的楼");
        building.setSort(0);
        building.setStatus(1);
        buildingMapper.insert(building);
        return building.getId();
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
