package com.bluemalic.repair;

import com.bluemalic.repair.entity.SysUser;
import com.bluemalic.repair.entity.SysUserRole;
import com.bluemalic.repair.entity.TicketLog;
import com.bluemalic.repair.entity.WorkerBuilding;
import com.bluemalic.repair.mapper.SysUserMapper;
import com.bluemalic.repair.mapper.SysUserRoleMapper;
import com.bluemalic.repair.mapper.TicketLogMapper;
import com.bluemalic.repair.mapper.WorkerBuildingMapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
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
 * 学生验收不通过（打回重做，`40 → 30`）——`docs/01` §4.1。
 *
 * <p>这一条最容易和"驳回"混掉，所以断言盯的就是两者的**区别**：打回**保留 `worker_id`**
 * （还是这位师傅返工），驳回会清空它（退回调度池）。另外三条也在这里钉住：
 * 只有本人能打回、理由必填、打回后能再次完工走完整个回路。
 */
@IntegrationTest
class TicketReworkTest {

    private static final String PASSWORD = "Test@123456";
    private static final long ROLE_STUDENT = 1L;
    private static final long ROLE_WORKER = 2L;
    private static final long ROLE_ADMIN = 3L;

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
    private TicketLogMapper ticketLogMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    // ==================== 正向：打回 → 同一师傅返工 → 再验收 ====================

    @Test
    void reworkKeepsTheSameWorkerAndTheTicketCanBeFinishedAgain() throws Exception {
        String student = givenToken("test-rw-student", 1, ROLE_STUDENT, null);
        String studentOther = givenToken("test-rw-student2", 1, ROLE_STUDENT, null);
        String worker = givenToken("test-rw-worker", 2, ROLE_WORKER, List.of(1L));
        String admin = givenToken("test-rw-admin", 3, ROLE_ADMIN, null);
        String ticketId = toVerify(student, admin, worker, "test-rw-worker");

        long workerId = userIdOf("test-rw-worker");

        // 别人不能替你打回：工单对他**根本不可见**（拦截器注入了 student_id = 本人），
        // 所以是 20001「工单不存在」，不是 20004「无权操作他人工单」——
        // 与跨租户那条规则一致：不告诉调用方"这单存在、但不属于你"。
        // 服务里那句 studentId 比对是第二道防线（拦截器已经在 SQL 层限制了），这条路径走不到它
        postJson(studentOther, "/api/student/tickets/" + ticketId + "/rework",
                Map.of("reason", "我没报过这单"))
                .andExpect(jsonPath("$.code").value(20001));

        // 本人打回：40 → 30，师傅不变
        postJson(student, "/api/student/tickets/" + ticketId + "/rework",
                Map.of("reason", "水管还在滴，接头没拧紧"))
                .andExpect(jsonPath("$.code").value(0));
        JsonNode afterRework = getJson(student, "/api/student/tickets/" + ticketId).path("data");
        assertThat(afterRework.path("status").asInt()).isEqualTo(30);
        assertThat(afterRework.path("workerId").asLong()).as("打回要保留师傅（与驳回的区别）").isEqualTo(workerId);

        // 理由进了流转日志，时间线上看得见
        assertThat(logRemarks(ticketId)).contains("水管还在滴，接头没拧紧");
        assertThat(actionsOf(ticketId)).contains("REWORK");
        // 打回后重新登记"未处理升级"（基准仍是派单时间）
        assertThat(stringRedisTemplate.opsForZSet().score("ticket:timeout:PROCESS", ticketId))
                .as("打回后应重新登记处理超时节点").isNotNull();

        // 师傅侧：这单回到他的「我的任务」，能再次完工
        assertThat(listIds(worker, "/api/worker/tickets")).contains(ticketId);
        postJson(worker, "/api/worker/tickets/" + ticketId + "/finish",
                Map.of("resultDesc", "重新拧紧了接头并试水", "resultImages", List.of()))
                .andExpect(jsonPath("$.code").value(0));

        // 再验收通过 → 50，评价照常写入（打回本身不写评价表）
        postJson(student, "/api/student/tickets/" + ticketId + "/evaluate", Map.of("score", 5))
                .andExpect(jsonPath("$.code").value(0));
        assertThat(statusOf(student, ticketId)).isEqualTo(50);
    }

    // ==================== 边界：状态与参数 ====================

    @Test
    void reworkIsRejectedOutsideToVerify() throws Exception {
        String student = givenToken("test-rw-state-student", 1, ROLE_STUDENT, null);
        String worker = givenToken("test-rw-state-worker", 2, ROLE_WORKER, List.of(1L));
        String admin = givenToken("test-rw-state-admin", 3, ROLE_ADMIN, null);
        String ticketId = toVerify(student, admin, worker, "test-rw-state-worker");

        // 先正常验收通过 → 50 已完成，此时不能再打回
        postJson(student, "/api/student/tickets/" + ticketId + "/evaluate", Map.of("score", 4))
                .andExpect(jsonPath("$.code").value(0));
        postJson(student, "/api/student/tickets/" + ticketId + "/rework", Map.of("reason", "再想想"))
                .andExpect(jsonPath("$.code").value(20002));
    }

    @Test
    void reworkRequiresAReason() throws Exception {
        String student = givenToken("test-rw-reason-student", 1, ROLE_STUDENT, null);
        String worker = givenToken("test-rw-reason-worker", 2, ROLE_WORKER, List.of(1L));
        String admin = givenToken("test-rw-reason-admin", 3, ROLE_ADMIN, null);
        String ticketId = toVerify(student, admin, worker, "test-rw-reason-worker");

        // 理由为空 → 参数校验层拦下（10001），不该落到"打回了但师傅不知道为啥"
        postJson(student, "/api/student/tickets/" + ticketId + "/rework", Map.of("reason", "   "))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(10001));
        assertThat(statusOf(student, ticketId)).isEqualTo(40);
    }

    @Test
    void workerCannotReworkAndStudentCannotFinish() throws Exception {
        String student = givenToken("test-rw-role-student", 1, ROLE_STUDENT, null);
        String worker = givenToken("test-rw-role-worker", 2, ROLE_WORKER, List.of(1L));
        String admin = givenToken("test-rw-role-admin", 3, ROLE_ADMIN, null);
        String ticketId = toVerify(student, admin, worker, "test-rw-role-worker");

        // 维修工没有 ticket:evaluate 权限：打回是学生的动作
        postJson(worker, "/api/student/tickets/" + ticketId + "/rework", Map.of("reason", "我自己打回"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(10003));
        // 反过来，学生也不能替师傅完工
        postJson(student, "/api/worker/tickets/" + ticketId + "/finish", Map.of("resultDesc", "我修好了"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(10003));
    }

    // ==================== 工具：走到 40 待验收 ====================

    /** 提交 → 派单 → 接单 → 到场（用种子里的报修码）→ 完工，停在 40 待验收。 */
    private String toVerify(String student, String admin, String worker, String workerUsername) throws Exception {
        String ticketId = submitTicket(student);
        postJson(admin, "/api/admin/tickets/" + ticketId + "/dispatch",
                Map.of("workerId", userIdOf(workerUsername))).andExpect(jsonPath("$.code").value(0));
        postJson(worker, "/api/worker/tickets/" + ticketId + "/accept", Map.of())
                .andExpect(jsonPath("$.code").value(0));
        postJson(worker, "/api/worker/tickets/" + ticketId + "/arrive", Map.of("repairCode", "482913"))
                .andExpect(jsonPath("$.code").value(0));
        postJson(worker, "/api/worker/tickets/" + ticketId + "/finish",
                Map.of("resultDesc", "换了密封圈", "resultImages", List.of()))
                .andExpect(jsonPath("$.code").value(0));
        return ticketId;
    }

    private String submitTicket(String token) throws Exception {
        MvcResult result = postJson(token, "/api/student/tickets", Map.of(
                        "repairCode", "482913", "categoryId", 1, "description", "水管漏水"))
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path("id").asText();
    }

    private List<String> logRemarks(String ticketId) {
        return ticketLogMapper.selectList(Wrappers.<TicketLog>lambdaQuery()
                        .eq(TicketLog::getTicketId, Long.parseLong(ticketId)))
                .stream().map(TicketLog::getRemark).filter(java.util.Objects::nonNull).toList();
    }

    private List<String> actionsOf(String ticketId) {
        return ticketLogMapper.selectList(Wrappers.<TicketLog>lambdaQuery()
                        .eq(TicketLog::getTicketId, Long.parseLong(ticketId)))
                .stream().map(TicketLog::getAction).toList();
    }

    private List<String> listIds(String token, String url) throws Exception {
        JsonNode list = getJson(token, url).path("data").path("list");
        return java.util.stream.StreamSupport.stream(list.spliterator(), false)
                .map(node -> node.path("id").asText()).toList();
    }

    private int statusOf(String token, String ticketId) throws Exception {
        return getJson(token, "/api/student/tickets/" + ticketId).path("data").path("status").asInt();
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
        user.setMustChangePassword(0);
        sysUserMapper.insert(user);

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
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path("tokenValue").asText();
    }

    private long userIdOf(String username) {
        return sysUserMapper.selectOne(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getUsername, username)).getId();
    }

    private org.springframework.test.web.servlet.ResultActions postJson(String token, String url,
                                                                       Map<String, Object> body)
            throws Exception {
        return mockMvc.perform(post(url)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private JsonNode getJson(String token, String url) throws Exception {
        MvcResult result = mockMvc.perform(get(url).header("Authorization", "Bearer " + token)).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
