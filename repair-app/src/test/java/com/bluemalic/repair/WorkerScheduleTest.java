package com.bluemalic.repair;

import com.bluemalic.repair.entity.SysUser;
import com.bluemalic.repair.entity.SysUserRole;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.StreamSupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 工种分组 / 排班（阶段 3 · docs/01 §4.8）。重点在四条边界：
 * <ul>
 *   <li>成员只接受本租户启用中的维修工；</li>
 *   <li>组内还有人、或类别还在用这个工种时不能删组；</li>
 *   <li>排班按 (维修工, 日期, 班次) 覆盖，不产生重复行；</li>
 *   <li>两张新表都不在拦截器范围，跨租户必须看不见。</li>
 * </ul>
 *
 * <p>种子依赖：租户 1（gdou）、角色 3 后勤管理。
 */
@IntegrationTest
class WorkerScheduleTest {

    private static final String PASSWORD = "Test@123456";
    private static final String WORKER_PASSWORD = "Worker@123456";
    private static final long ROLE_ADMIN = 3L;
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
    void groupCrudAndMembers() throws Exception {
        String admin = givenToken("test-sched-admin");
        long groupId = createGroup(admin, "测试水电工", 1);
        long worker1 = createWorker(admin, "test-sched-w1", "张师傅");
        long worker2 = createWorker(admin, "test-sched-w2", "李师傅");

        setWorkers(admin, groupId, List.of(worker1, worker2))
                .andExpect(jsonPath("$.code").value(0));

        JsonNode group = findGroup(admin, groupId);
        assertThat(group.path("workerCount").asInt()).isEqualTo(2);
        assertThat(workerIdsOf(group)).containsExactlyInAnyOrder(worker1, worker2);

        // 组内还有人：不能删
        deleteJson(admin, "/api/admin/worker-groups/" + groupId)
                .andExpect(jsonPath("$.code").value(10001));

        // 清空后可以删
        setWorkers(admin, groupId, List.of()).andExpect(jsonPath("$.code").value(0));
        deleteJson(admin, "/api/admin/worker-groups/" + groupId)
                .andExpect(jsonPath("$.code").value(0));
        assertThat(groupNames(admin)).doesNotContain("测试水电工");
    }

    @Test
    void groupNameMustBeUnique() throws Exception {
        String admin = givenToken("test-sched-dup");
        createGroup(admin, "测试空调工", 1);
        postJson(admin, "/api/admin/worker-groups", Map.of("name", "测试空调工", "sort", 0))
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.message").value("工种名称已存在"));
    }

    @Test
    void categoryCanReferenceGroup() throws Exception {
        String admin = givenToken("test-sched-category");
        long groupId = createGroup(admin, "测试木工", 2);

        MvcResult created = postJson(admin, "/api/admin/categories", Map.of(
                "name", "测试门窗", "defaultUrgency", 1, "sort", 0, "workerGroupId", groupId))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        long categoryId = readJson(created).path("data").path("id").asLong();

        JsonNode category = findCategory(admin, categoryId);
        assertThat(category.path("workerGroupId").asLong()).isEqualTo(groupId);
        assertThat(category.path("workerGroupName").asText()).isEqualTo("测试木工");

        // 别家租户的分组不能挂到本租户的类别上
        long foreignGroup = System.nanoTime();
        jdbcTemplate.update("""
                INSERT INTO worker_group (id, tenant_id, name, sort, status, deleted, create_time, update_time)
                VALUES (?, 2, '外校工种', 0, 1, 0, NOW(), NOW())
                """, foreignGroup);
        postJson(admin, "/api/admin/categories", Map.of(
                "name", "测试外校工种", "defaultUrgency", 1, "sort", 0, "workerGroupId", foreignGroup))
                .andExpect(jsonPath("$.code").value(10001));
    }

    @Test
    void groupUsedByCategoryCannotBeDeleted() throws Exception {
        String admin = givenToken("test-sched-category-guard");
        long groupId = createGroup(admin, "测试电工", 3);
        postJson(admin, "/api/admin/categories", Map.of(
                "name", "测试电路", "defaultUrgency", 1, "sort", 0, "workerGroupId", groupId))
                .andExpect(jsonPath("$.code").value(0));

        deleteJson(admin, "/api/admin/worker-groups/" + groupId)
                .andExpect(jsonPath("$.code").value(10001));
    }

    @Test
    void shiftUpsertAndList() throws Exception {
        String admin = givenToken("test-sched-shift");
        long workerId = createWorker(admin, "test-sched-shift-w", "王师傅");

        putJson(admin, "/api/admin/worker-shifts", Map.of(
                "workerId", workerId, "shiftDate", "2026-11-01",
                "shiftType", 1, "status", 1, "remark", "正常上班"))
                .andExpect(jsonPath("$.code").value(0));

        JsonNode first = getJson(admin, "/api/admin/worker-shifts", "date", "2026-11-01");
        assertThat(first.path("data")).hasSize(1);
        assertThat(first.path("data").get(0).path("status").asInt()).isEqualTo(1);
        assertThat(first.path("data").get(0).path("workerName").asText()).isEqualTo("王师傅");

        // 同 (维修工, 日期, 班次) 覆盖，不新增第二行
        putJson(admin, "/api/admin/worker-shifts", Map.of(
                "workerId", workerId, "shiftDate", "2026-11-01",
                "shiftType", 1, "status", 0, "remark", "临时请假"))
                .andExpect(jsonPath("$.code").value(0));
        JsonNode second = getJson(admin, "/api/admin/worker-shifts", "date", "2026-11-01");
        assertThat(second.path("data")).hasSize(1);
        assertThat(second.path("data").get(0).path("status").asInt()).isZero();
        assertThat(second.path("data").get(0).path("remark").asText()).isEqualTo("临时请假");

        JsonNode otherDay = getJson(admin, "/api/admin/worker-shifts", "date", "2026-11-02");
        assertThat(otherDay.path("data")).isEmpty();
    }

    @Test
    void shiftRejectsForeignOrNonWorker() throws Exception {
        String admin = givenToken("test-sched-shift-reject");

        long foreignWorker = System.nanoTime();
        jdbcTemplate.update("""
                INSERT INTO sys_user (id, tenant_id, username, password, real_name, user_type, status,
                                      must_change_password, deleted, create_time, update_time)
                VALUES (?, 2, 'test-sched-foreign', 'x', '外校师傅', 2, 1, 0, 0, NOW(), NOW())
                """, foreignWorker);
        putJson(admin, "/api/admin/worker-shifts", Map.of(
                "workerId", foreignWorker, "shiftDate", "2026-11-01", "shiftType", 1, "status", 1))
                .andExpect(jsonPath("$.code").value(10001));

        // 后勤账号不是维修工，不能排班
        SysUser adminUser = sysUserMapper.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers
                        .<SysUser>lambdaQuery().eq(SysUser::getUsername, "test-sched-shift-reject"))
                .stream().findFirst().orElseThrow();
        putJson(admin, "/api/admin/worker-shifts", Map.of(
                "workerId", adminUser.getId(), "shiftDate", "2026-11-01", "shiftType", 1, "status", 1))
                .andExpect(jsonPath("$.code").value(10001));
    }

    @Test
    void groupsAndShiftsAreTenantIsolated() throws Exception {
        String admin = givenToken("test-sched-isolation");
        long foreignGroup = System.nanoTime();
        jdbcTemplate.update("""
                INSERT INTO worker_group (id, tenant_id, name, sort, status, deleted, create_time, update_time)
                VALUES (?, 2, '隔离测试工种', 0, 1, 0, NOW(), NOW())
                """, foreignGroup);
        assertThat(groupNames(admin)).doesNotContain("隔离测试工种");

        long foreignShift = System.nanoTime();
        long foreignWorker = System.nanoTime() + 1;
        jdbcTemplate.update("""
                INSERT INTO sys_user (id, tenant_id, username, password, real_name, user_type, status,
                                      must_change_password, deleted, create_time, update_time)
                VALUES (?, 2, 'test-sched-iso-w', 'x', '外校王师傅', 2, 1, 0, 0, NOW(), NOW())
                """, foreignWorker);
        jdbcTemplate.update("""
                INSERT INTO worker_shift (id, tenant_id, worker_id, shift_date, shift_type, status,
                                          create_time, update_time)
                VALUES (?, 2, ?, '2026-11-01', 1, 1, NOW(), NOW())
                """, foreignShift, foreignWorker);
        JsonNode shifts = getJson(admin, "/api/admin/worker-shifts", "date", "2026-11-01");
        assertThat(shifts.path("data")).isEmpty();
    }

    // ==================== 工具 ====================

    private long createGroup(String admin, String name, int sort) throws Exception {
        MvcResult result = postJson(admin, "/api/admin/worker-groups", Map.of("name", name, "sort", sort))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        return readJson(result).path("data").path("id").asLong();
    }

    private ResultActions setWorkers(String admin, long groupId, List<Long> workerIds) throws Exception {
        return putJson(admin, "/api/admin/worker-groups/" + groupId + "/workers",
                Map.of("workerIds", workerIds));
    }

    private long createWorker(String admin, String username, String realName) throws Exception {
        MvcResult result = postJson(admin, "/api/admin/workers", Map.of(
                "username", username, "realName", realName, "phone", "13800000000",
                "password", WORKER_PASSWORD))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        return readJson(result).path("data").path("id").asLong();
    }

    private JsonNode findGroup(String admin, long groupId) throws Exception {
        for (JsonNode node : getJson(admin, "/api/admin/worker-groups").path("data")) {
            if (node.path("id").asLong() == groupId) {
                return node;
            }
        }
        throw new AssertionError("分组不存在：" + groupId);
    }

    private List<String> groupNames(String admin) throws Exception {
        JsonNode groups = getJson(admin, "/api/admin/worker-groups").path("data");
        return StreamSupport.stream(groups.spliterator(), false)
                .map(node -> node.path("name").asText()).toList();
    }

    private List<Long> workerIdsOf(JsonNode group) {
        return StreamSupport.stream(group.path("workerIds").spliterator(), false)
                .map(JsonNode::asLong).toList();
    }

    private JsonNode findCategory(String admin, long categoryId) throws Exception {
        for (JsonNode node : getJson(admin, "/api/admin/categories").path("data")) {
            if (node.path("id").asLong() == categoryId) {
                return node;
            }
        }
        throw new AssertionError("类别不存在：" + categoryId);
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
