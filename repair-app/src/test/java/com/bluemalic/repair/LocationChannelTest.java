package com.bluemalic.repair;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bluemalic.repair.entity.RepairCode;
import com.bluemalic.repair.entity.SysUser;
import com.bluemalic.repair.entity.SysUserRole;
import com.bluemalic.repair.entity.TicketCategory;
import com.bluemalic.repair.entity.WorkerBuilding;
import com.bluemalic.repair.mapper.RepairCodeMapper;
import com.bluemalic.repair.mapper.SysUserMapper;
import com.bluemalic.repair.mapper.SysUserRoleMapper;
import com.bluemalic.repair.mapper.TicketCategoryMapper;
import com.bluemalic.repair.mapper.WorkerBuildingMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 报修位置的两条入口（docs/01 §4.6）：扫码/输码，以及**手动选位置**（公共区域没有码可扫时用）。
 *
 * <p>本类钉住设计里那条不变量：**每个可选位置都有一张码**——
 * 所以"手动选位置"列出来的就是报修码清单，公共区域也有自己的"区域码"，
 * 到场核验（`arrive`）对宿舍和公共区域走的是同一段逻辑，不需要特判。
 */
@IntegrationTest
class LocationChannelTest {

    private static final String PASSWORD = "Test@123456";
    private static final long ROLE_STUDENT = 1L;
    private static final long ROLE_WORKER = 2L;
    private static final long ROLE_ADMIN = 3L;
    private static final long TENANT = 1L;
    private static final long BUILDING = 1L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private SysUserRoleMapper sysUserRoleMapper;

    @Autowired
    private TicketCategoryMapper ticketCategoryMapper;

    @Autowired
    private RepairCodeMapper repairCodeMapper;

    @Autowired
    private WorkerBuildingMapper workerBuildingMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private String student;
    private String admin;
    private long waterCategoryId;

    @BeforeEach
    void setUp() throws Exception {
        student = givenToken("test-loc-student", 1, ROLE_STUDENT);
        admin = givenToken("test-loc-admin", 3, ROLE_ADMIN);
        waterCategoryId = ticketCategoryMapper.selectOne(Wrappers.<TicketCategory>lambdaQuery()
                .eq(TicketCategory::getTenantId, TENANT).eq(TicketCategory::getName, "水电")).getId();
    }

    @Test
    void 位置清单按楼栋分组_宿舍与公共区域都在() throws Exception {
        givenCode("990001", BUILDING, "9-901", 1);
        givenCode("990002", BUILDING, "9F-走廊", 2);

        JsonNode buildings = locations(student);
        JsonNode target = null;
        for (JsonNode building : buildings) {
            if (building.path("buildingId").asLong() == BUILDING) {
                target = building;
            }
        }
        assertThat(target).as("楼栋 %s 应出现在位置清单里", BUILDING).isNotNull();
        assertThat(target.path("buildingName").asText()).isNotBlank();

        JsonNode locations = target.path("locations");
        assertThat(hasLocation(locations, "9-901", 1)).as("宿舍房间应出现且类型为 1").isTrue();
        assertThat(hasLocation(locations, "9F-走廊", 2)).as("公共区域应出现且类型为 2").isTrue();
    }

    /** 本表不在数据权限拦截器名单里：租户条件全靠 SQL 自己写，漏了就是列出别校的位置。 */
    @Test
    void 别的租户的位置不会出现() throws Exception {
        givenCode("990003", BUILDING, "99-别的学校", 1, 99L);

        JsonNode buildings = locations(student);
        for (JsonNode building : buildings) {
            for (JsonNode location : building.path("locations")) {
                assertThat(location.path("room").asText()).isNotEqualTo("99-别的学校");
            }
        }
    }

    /**
     * 端到端：公共区域手选位置报修 → 派单 → 接单 → **扫区域码到场**。
     *
     * <p>这条是这次改动的核心断言：公共区域**不靠"每间房一码"**，它有自己的区域码，
     * 到场校验与宿舍完全同一套逻辑（`arrive` 比对码对应的楼栋+位置与工单一致）。
     */
    @Test
    void 手选公共区域报修后_扫区域码能完成到场() throws Exception {
        String zoneCode = "990004";
        givenCode(zoneCode, BUILDING, "1F-大厅", 2);
        long workerId = givenWorkerResponsibleFor(BUILDING);

        // 1. 学生手选位置（不带 repairCode）
        Map<String, Object> submit = new HashMap<>();
        submit.put("buildingId", BUILDING);
        submit.put("room", "1F-大厅");
        submit.put("categoryId", waterCategoryId);
        submit.put("description", "大厅吊灯不亮");
        submit.put("urgency", 1);
        submit.put("idempotencyKey", "test-" + System.nanoTime());
        MvcResult created = mockMvc.perform(post("/api/student/tickets")
                        .header("Authorization", "Bearer " + student)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(submit)))
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        long ticketId = json(created).path("data").path("id").asLong();
        assertThat(json(created).path("data").path("room").asText()).isEqualTo("1F-大厅");

        // 2. 后勤派给该楼栋的师傅 → 师傅接单
        mockMvc.perform(post("/api/admin/tickets/" + ticketId + "/dispatch")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("workerId", workerId))))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(post("/api/worker/tickets/" + ticketId + "/accept")
                        .header("Authorization", "Bearer " + workerOf(BUILDING)))
                .andExpect(jsonPath("$.code").value(0));

        // 3. 到场：扫的是**区域码**，与宿舍用同一套校验
        mockMvc.perform(post("/api/worker/tickets/" + ticketId + "/arrive")
                        .header("Authorization", "Bearer " + workerOf(BUILDING))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("repairCode", zoneCode))))
                .andExpect(jsonPath("$.code").value(0));
    }

    /** 管理端建"公共区域码"：类型落库、位置清单里能看到。 */
    @Test
    void 管理端可以给公共区域建码() throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("buildingId", BUILDING);
        body.put("room", "2F-茶水间");
        body.put("locationType", 2);

        MvcResult created = mockMvc.perform(post("/api/admin/repair-codes")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        assertThat(json(created).path("data").path("locationType").asInt()).isEqualTo(2);

        JsonNode target = null;
        for (JsonNode building : locations(student)) {
            if (building.path("buildingId").asLong() == BUILDING) {
                target = building;
            }
        }
        assertThat(target).isNotNull();
        assertThat(hasLocation(target.path("locations"), "2F-茶水间", 2)).isTrue();
    }

    // ==================== 工具 ====================

    private JsonNode locations(String token) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/locations").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        return json(result).path("data");
    }

    private boolean hasLocation(JsonNode locations, String room, int type) {
        for (JsonNode location : locations) {
            if (room.equals(location.path("room").asText()) && location.path("locationType").asInt() == type) {
                return true;
            }
        }
        return false;
    }

    private void givenCode(String code, long buildingId, String room, int type) {
        givenCode(code, buildingId, room, type, TENANT);
    }

    private void givenCode(String code, long buildingId, String room, int type, long tenantId) {
        RepairCode entity = new RepairCode();
        entity.setTenantId(tenantId);
        entity.setCode(code);
        entity.setBuildingId(buildingId);
        entity.setRoom(room);
        entity.setLocationType(type);
        entity.setStatus(1);
        repairCodeMapper.insert(entity);
    }

    /** 造一个"负责该楼栋"的师傅，返回 userId（token 需要用户名，单独记一下）。 */
    private long givenWorkerResponsibleFor(long buildingId) throws Exception {
        SysUser worker = new SysUser();
        worker.setTenantId(TENANT);
        worker.setUsername("test-loc-worker");
        worker.setPassword(passwordEncoder.encode(PASSWORD));
        worker.setRealName("测试师傅");
        worker.setUserType(2);
        worker.setStatus(1);
        sysUserMapper.insert(worker);

        SysUserRole link = new SysUserRole();
        link.setUserId(worker.getId());
        link.setRoleId(ROLE_WORKER);
        sysUserRoleMapper.insert(link);

        WorkerBuilding responsibility = new WorkerBuilding();
        responsibility.setTenantId(TENANT);
        responsibility.setWorkerId(worker.getId());
        responsibility.setBuildingId(buildingId);
        workerBuildingMapper.insert(responsibility);
        return worker.getId();
    }

    /** 师傅的 token 在用例里只登录一次，这里按需再登（同账号多端登录是允许的）。 */
    private String workerOf(long buildingId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "tenantCode", "gdou", "username", "test-loc-worker", "password", PASSWORD))))
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        return json(result).path("data").path("tokenValue").asText();
    }

    private String givenToken(String username, int userType, long roleId) throws Exception {
        SysUser user = new SysUser();
        user.setTenantId(TENANT);
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
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        return json(result).path("data").path("tokenValue").asText();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

}
