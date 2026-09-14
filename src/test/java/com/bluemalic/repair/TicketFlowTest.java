package com.bluemalic.repair;

import com.bluemalic.repair.entity.SysUser;
import com.bluemalic.repair.entity.SysUserRole;
import com.bluemalic.repair.entity.WorkerBuilding;
import com.bluemalic.repair.mapper.SysUserMapper;
import com.bluemalic.repair.mapper.SysUserRoleMapper;
import com.bluemalic.repair.mapper.WorkerBuildingMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * M2 验收测试：一个工单能从提交走到关闭、非法跃迁被拒、越权被拦（docs/01 里程碑 M2）。
 *
 * <p>种子数据依赖 schema.sql：租户 gdou、角色 1学生/2维修工/3后勤、楼栋 1~5、
 * 类别 1~6、报修码 482913→1号楼1-101。
 */
@IntegrationTest
class TicketFlowTest {

    private static final String PASSWORD = "Test@123456";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private SysUserRoleMapper sysUserRoleMapper;

    @Autowired
    private WorkerBuildingMapper workerBuildingMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void fullLifecycleFromSubmitToClose() throws Exception {
        String student = givenToken("test-flow-student", 1, 1L, null);
        String worker = givenToken("test-flow-worker", 2, 2L, List.of(1L));
        String admin = givenToken("test-flow-admin", 3, 3L, null);

        // 1. 学生扫码提交（报修码 482913 = 1号楼 1-101）→ 10
        String ticketId = submitTicket(student, null);

        // 2. 后勤派单 → 20
        postJson(admin, "/api/admin/tickets/" + ticketId + "/dispatch",
                Map.of("workerId", userIdOf("test-flow-worker")))
                .andExpect(jsonPath("$.code").value(0));
        assertThat(statusOf(admin, ticketId)).isEqualTo(20);

        // 非法跃迁①：重复派单 → 20002
        postJson(admin, "/api/admin/tickets/" + ticketId + "/dispatch",
                Map.of("workerId", userIdOf("test-flow-worker")))
                .andExpect(jsonPath("$.code").value(20002));

        // 3. 维修工接单 → 30
        postJson(worker, "/api/worker/tickets/" + ticketId + "/accept", Map.of())
                .andExpect(jsonPath("$.code").value(0));
        assertThat(statusOf(admin, ticketId)).isEqualTo(30);

        // 4. 到场打卡：报修码对得上 → 200（状态不变，记录时间）
        postJson(worker, "/api/worker/tickets/" + ticketId + "/arrive",
                Map.of("repairCode", "482913")).andExpect(jsonPath("$.code").value(0));

        // 非法跃迁②：扫别的房间的码 → 20007
        postJson(worker, "/api/worker/tickets/" + ticketId + "/arrive",
                Map.of("repairCode", "306718")).andExpect(jsonPath("$.code").value(20007));

        // 5. 完工上报 → 40
        postJson(worker, "/api/worker/tickets/" + ticketId + "/finish", Map.of(
                "resultDesc", "更换水龙头",
                "resultImages", List.of("https://img.example.com/after.jpg")))
                .andExpect(jsonPath("$.code").value(0));
        assertThat(statusOf(admin, ticketId)).isEqualTo(40);

        // 6. 学生验收评价 → 50
        postJson(student, "/api/student/tickets/" + ticketId + "/evaluate",
                Map.of("score", 5, "content", "修得很快"))
                .andExpect(jsonPath("$.code").value(0));
        assertThat(statusOf(admin, ticketId)).isEqualTo(50);

        // 7. 后勤关闭 → 60
        postJson(admin, "/api/admin/tickets/" + ticketId + "/close", Map.of())
                .andExpect(jsonPath("$.code").value(0));
        assertThat(statusOf(admin, ticketId)).isEqualTo(60);

        // 通知回归（B4 收敛后的触发不改变数量与接收方）：
        // 学生应已收 3 条（接单/到场/完工），维修工 2 条（派单/评价）
        JsonNode studentUnread = getJson(student, "/api/notifications/unread-count");
        assertThat(studentUnread.get("data").asInt()).isEqualTo(3);
        JsonNode workerUnread = getJson(worker, "/api/notifications/unread-count");
        assertThat(workerUnread.get("data").asInt()).isEqualTo(2);

        // 非法跃迁③：已关闭再评价 → 20002；终态不能再流转
        postJson(student, "/api/student/tickets/" + ticketId + "/evaluate",
                Map.of("score", 1)).andExpect(jsonPath("$.code").value(20002));

        // 流转时间线完整：SUBMIT/DISPATCH/ACCEPT/ARRIVE/FINISH/EVALUATE/CLOSE 共 7 条
        JsonNode detail = getJson(admin, "/api/admin/tickets/" + ticketId);
        JsonNode logs = detail.get("data").get("logs");
        assertThat(logs).hasSize(7);
        assertThat(logs.get(0).get("action").asText()).isEqualTo("SUBMIT");
        assertThat(logs.get(6).get("action").asText()).isEqualTo("CLOSE");
        // 响应时长有值（到场打卡在派单之后）
        assertThat(detail.get("data").get("arriveMinutes").isNumber()).isTrue();
    }

    @Test
    void dataScopeKeepsEachRoleWithinItsBoundary() throws Exception {
        String studentA = givenToken("test-scope-student-a", 1, 1L, null);
        String studentB = givenToken("test-scope-student-b", 1, 1L, null);
        String workerInBuilding1 = givenToken("test-scope-worker-1", 2, 2L, List.of(1L, 3L));
        String workerInBuilding3Only = givenToken("test-scope-worker-3", 2, 2L, List.of(3L));
        String workerPeerInBuilding1 = givenToken("test-scope-worker-peer", 2, 2L, List.of(1L));
        String admin = givenToken("test-scope-admin", 3, 3L, null);

        String ticketA = submitTicket(studentA, null); // 1号楼（报修码）
        String ticketB = submitTicket(studentB, null);

        // 学生：只看到自己的单
        JsonNode listA = getJson(studentA, "/api/student/tickets");
        assertThat(firstListIds(listA)).containsExactly(ticketA);

        // 维修工（负责 1、3 号楼）：能看到 1 号楼的 A 单
        JsonNode listW1 = getJson(workerInBuilding1, "/api/worker/tickets");
        assertThat(firstListIds(listW1)).contains(ticketA);

        // 维修工（只负责 3 号楼）：1 号楼的 A 单不可见，详情 20001
        JsonNode listW3 = getJson(workerInBuilding3Only, "/api/worker/tickets");
        assertThat(firstListIds(listW3)).doesNotContain(ticketA);
        getJsonRaw(workerInBuilding3Only, "/api/worker/tickets/" + ticketA)
                .andExpect(jsonPath("$.code").value(20001));

        // 并发抢单：单派给 worker-1；同楼栋的另一个维修工"看得到"，但接单 → 20003
        postJson(admin,
                "/api/admin/tickets/" + ticketA + "/dispatch",
                Map.of("workerId", userIdOf("test-scope-worker-1"))).andExpect(jsonPath("$.code").value(0));
        postJson(workerPeerInBuilding1, "/api/worker/tickets/" + ticketA + "/accept", Map.of())
                .andExpect(jsonPath("$.code").value(20003));

        // 功能权限：学生调后勤接口 → 403（Sa-Token 注解拦截）
        postJson(studentA, "/api/admin/tickets/" + ticketA + "/dispatch",
                Map.of("workerId", userIdOf("test-scope-worker-1")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(10003));

        // 撤单：学生撤自己的（10 → 70）
        postJson(studentB, "/api/student/tickets/" + ticketB + "/cancel", Map.of())
                .andExpect(jsonPath("$.code").value(0));
        assertThat(statusOf(studentB, ticketB)).isEqualTo(70);
    }

    @Test
    void byCodeReturnsLocationOrInvalid() throws Exception {
        String student = givenToken("test-code-student", 1, 1L, null);

        JsonNode ok = getJson(student, "/api/tickets/by-code/482913");
        assertThat(ok.get("data").get("buildingName").asText()).isEqualTo("1号楼");
        assertThat(ok.get("data").get("room").asText()).isEqualTo("1-101");

        getJsonRaw(student, "/api/tickets/by-code/000000")
                .andExpect(jsonPath("$.code").value(20006));
    }

    // ==================== 工具 ====================

    private String submitTicket(String token, String repairCode) throws Exception {
        // 始终用 1 号楼的报修码提交，方便按楼栋断言数据范围
        MvcResult result = postJson(token, "/api/student/tickets", Map.of(
                        "repairCode", "482913", "categoryId", 1, "description", "水管漏水"))
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        JsonNode node = objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        return node.path("data").path("id").asText();
    }

    private int statusOf(String token, String ticketId) throws Exception {
        JsonNode node = getJson(token, "/api/student/tickets/" + ticketId);
        if (node.get("code").asInt() != 0) {
            node = getJson(token, "/api/worker/tickets/" + ticketId);
        }
        if (node.get("code").asInt() != 0) {
            node = getJson(token, "/api/admin/tickets/" + ticketId);
        }
        return node.path("data").path("status").asInt();
    }

    private List<String> firstListIds(JsonNode pageResult) {
        JsonNode list = pageResult.get("data").get("list");
        if (list == null) {
            return List.of();
        }
        return java.util.stream.StreamSupport.stream(list.spliterator(), false)
                .map(n -> n.get("id").asText()).toList();
    }

    private org.springframework.test.web.servlet.ResultActions postJson(String token, String url, Map<String, Object> body) throws Exception {
        return mockMvc.perform(post(url)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)));
    }

    private JsonNode getJson(String token, String url) throws Exception {
        MvcResult result = getJsonRaw(token, url).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private org.springframework.test.web.servlet.ResultActions getJsonRaw(String token, String url) throws Exception {
        return mockMvc.perform(get(url).header("Authorization", "Bearer " + token));
    }

    /** 造用户 + 角色 + （维修工的）负责楼栋，登录拿 token。 */
    private String givenToken(String username, int userType, long roleId, List<Long> buildingIds) throws Exception {
        SysUser user = new SysUser();
        user.setTenantId(1L);
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setRealName(username);
        user.setUserType(userType);
        user.setStatus(1);
        assertThat(sysUserMapper.insert(user)).isEqualTo(1);

        SysUserRole link = new SysUserRole();
        link.setUserId(user.getId());
        link.setRoleId(roleId);
        sysUserRoleMapper.insert(link);

        if (buildingIds != null) {
            for (Long buildingId : buildingIds) {
                WorkerBuilding wb = new WorkerBuilding();
                wb.setTenantId(1L);
                wb.setWorkerId(user.getId());
                wb.setBuildingId(buildingId);
                workerBuildingMapper.insert(wb);
            }
        }

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "tenantCode", "gdou", "username", username, "password", PASSWORD))))
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        JsonNode node = objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        return node.path("data").path("tokenValue").asText();
    }

    private long userIdOf(String username) {
        return sysUserMapper.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<SysUser>lambdaQuery()
                        .eq(SysUser::getUsername, username)).getId();
    }
}
