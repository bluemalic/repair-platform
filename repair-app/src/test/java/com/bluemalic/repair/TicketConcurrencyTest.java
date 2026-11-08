package com.bluemalic.repair;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bluemalic.repair.entity.SysUser;
import com.bluemalic.repair.entity.SysUserRole;
import com.bluemalic.repair.entity.Ticket;
import com.bluemalic.repair.entity.WorkerBuilding;
import com.bluemalic.repair.mapper.SysUserMapper;
import com.bluemalic.repair.mapper.SysUserRoleMapper;
import com.bluemalic.repair.mapper.TicketMapper;
import com.bluemalic.repair.mapper.WorkerBuildingMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 真正并发的正确性测试：两个请求在同一时刻抢同一张工单。
 *
 * <p>已有的状态机测试大多是"顺序模拟"——先让 A 成功，再让 B 失败。
 * 这条测试用 {@link CountDownLatch} 把两个请求真正同时发出去，盯住三件事：
 * 派单只能成功一次、接单只能成功一次、同一幂等键只能落一张单。
 *
 * <p>为什么值得单独测：状态机里的 {@code UPDATE ... WHERE status = 旧状态} 就是为并发写的；
 * 如果只做顺序断言，某次改动把条件更新换成 {@code updateById}，顺序测试仍然会绿。
 */
@IntegrationTest
// 并发请求跑在别的线程/连接上，测试事务里未提交的用户与工单它们看不见；
// 这条测试必须让 setup 真正提交，所以关掉测试事务（数据用唯一前缀隔离，不依赖回滚）。
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TicketConcurrencyTest {

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
    private TicketMapper ticketMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    /** 这条测试关掉了事务回滚，必须自己清理；否则会污染其它按列表断言的用例。 */
    private final Set<Long> ticketIds = ConcurrentHashMap.newKeySet();
    private final Set<Long> userIds = ConcurrentHashMap.newKeySet();
    private final AtomicInteger seq = new AtomicInteger();

    @Test
    void twoAdminsCannotDispatchTheSameTicket() throws Exception {
        String student = givenToken(unique("cc-admin-student"), 1, ROLE_STUDENT, null);
        String adminA = givenToken(unique("cc-admin-a"), 3, ROLE_ADMIN, null);
        String adminB = givenToken(unique("cc-admin-b"), 3, ROLE_ADMIN, null);
        String workerName = unique("cc-admin-worker");
        givenToken(workerName, 2, ROLE_WORKER, List.of(1L));
        String ticketId = submit(student);
        long workerId = userIdOf(workerName);

        List<Integer> codes = runTogether(
                () -> postCode(adminA, "/api/admin/tickets/" + ticketId + "/dispatch",
                        Map.of("workerId", workerId)),
                () -> postCode(adminB, "/api/admin/tickets/" + ticketId + "/dispatch",
                        Map.of("workerId", workerId)));

        assertThat(codes).as("两个调度员同时派单，只能有一个成功").containsExactlyInAnyOrder(0, 20002);
        assertThat(ticketMapper.selectById(Long.parseLong(ticketId)).getStatus()).isEqualTo(20);
    }

    @Test
    void twoAcceptsByTheSameWorkerOnlySucceedOnce() throws Exception {
        String student = givenToken(unique("cc-accept-student"), 1, ROLE_STUDENT, null);
        String admin = givenToken(unique("cc-accept-admin"), 3, ROLE_ADMIN, null);
        String workerName = unique("cc-accept-worker");
        String worker = givenToken(workerName, 2, ROLE_WORKER, List.of(1L));
        String ticketId = submit(student);
        postJson(admin, "/api/admin/tickets/" + ticketId + "/dispatch",
                Map.of("workerId", userIdOf(workerName))).andExpect(jsonPath("$.code").value(0));

        List<Integer> codes = runTogether(
                () -> postCode(worker, "/api/worker/tickets/" + ticketId + "/accept", Map.of()),
                () -> postCode(worker, "/api/worker/tickets/" + ticketId + "/accept", Map.of()));

        assertThat(codes).as("同一个维修工重复接单，只能成功一次").containsExactlyInAnyOrder(0, 20002);
        assertThat(ticketMapper.selectById(Long.parseLong(ticketId)).getStatus()).isEqualTo(30);
    }

    @Test
    void twoSubmitsWithTheSameIdempotencyKeyCreateOneTicket() throws Exception {
        String student = givenToken(unique("cc-idem-student"), 1, ROLE_STUDENT, null);
        String key = "cc-idem-" + System.nanoTime();

        List<JsonNode> results = runTogetherJson(
                () -> submitWithKey(student, key, "并发提交 A"),
                () -> submitWithKey(student, key, "并发提交 B"));

        assertThat(results).allSatisfy(r -> assertThat(r.path("code").asInt()).isEqualTo(0));
        assertThat(results.get(0).path("data").path("id").asText())
                .isEqualTo(results.get(1).path("data").path("id").asText());
        assertThat(ticketMapper.selectCount(Wrappers.<Ticket>lambdaQuery()
                .eq(Ticket::getIdempotencyKey, key))).isEqualTo(1);
    }

    @AfterEach
    void cleanUp() {
        for (Long ticketId : ticketIds) {
            jdbcTemplate.update("DELETE FROM consumed_event WHERE event_id IN "
                    + "(SELECT event_id FROM outbox_event WHERE aggregate_type = 'TICKET' AND aggregate_id = ?)", ticketId);
            jdbcTemplate.update("DELETE FROM outbox_event WHERE aggregate_type = 'TICKET' AND aggregate_id = ?", ticketId);
            jdbcTemplate.update("DELETE FROM notification WHERE ticket_id = ?", ticketId);
            jdbcTemplate.update("DELETE FROM ticket_evaluation WHERE ticket_id = ?", ticketId);
            jdbcTemplate.update("DELETE FROM ticket_collaborator WHERE ticket_id = ?", ticketId);
            jdbcTemplate.update("DELETE FROM ticket_log WHERE ticket_id = ?", ticketId);
            jdbcTemplate.update("DELETE FROM ticket WHERE id = ?", ticketId);
        }
        for (Long userId : userIds) {
            jdbcTemplate.update("DELETE FROM sys_user_role WHERE user_id = ?", userId);
            jdbcTemplate.update("DELETE FROM worker_building WHERE worker_id = ?", userId);
            jdbcTemplate.update("DELETE FROM sys_user WHERE id = ?", userId);
        }
        ticketIds.clear();
        userIds.clear();
    }

    // ==================== 并发工具 ====================

    /** 两个任务在同一道闸门后同时出发；返回值按任务顺序排列。 */
    private List<Integer> runTogether(ThrowingIntSupplier a, ThrowingIntSupplier b) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Future<Integer> fa = pool.submit(() -> {
                start.await();
                return a.get();
            });
            Future<Integer> fb = pool.submit(() -> {
                start.await();
                return b.get();
            });
            start.countDown();
            return List.of(fa.get(30, TimeUnit.SECONDS), fb.get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    private List<JsonNode> runTogetherJson(ThrowingJsonSupplier a, ThrowingJsonSupplier b) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Future<JsonNode> fa = pool.submit(() -> {
                start.await();
                return a.get();
            });
            Future<JsonNode> fb = pool.submit(() -> {
                start.await();
                return b.get();
            });
            start.countDown();
            return List.of(fa.get(30, TimeUnit.SECONDS), fb.get(30, TimeUnit.SECONDS));
        } finally {
            pool.shutdownNow();
        }
    }

    @FunctionalInterface
    private interface ThrowingIntSupplier {
        int get() throws Exception;
    }

    @FunctionalInterface
    private interface ThrowingJsonSupplier {
        JsonNode get() throws Exception;
    }

    private int postCode(String token, String url, Map<String, Object> body) throws Exception {
        return postJson(token, url, body).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8)
                .transform(this::codeOf);
    }

    private int codeOf(String json) {
        try {
            return objectMapper.readTree(json).path("code").asInt();
        } catch (Exception e) {
            throw new IllegalStateException("响应不是 JSON: " + json, e);
        }
    }

    private JsonNode submitWithKey(String token, String key, String description) throws Exception {
        MvcResult result = postJson(token, "/api/student/tickets", Map.of(
                        "idempotencyKey", key,
                        "repairCode", "482913", "categoryId", 1, "description", description))
                .andReturn();
        JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        if (node.path("code").asInt() == 0) {
            ticketIds.add(Long.parseLong(node.path("data").path("id").asText()));
        }
        return node;
    }

    private String submit(String token) throws Exception {
        JsonNode node = submitWithKey(token, "cc-" + System.nanoTime(), "【并发测试】水管漏水");
        assertThat(node.path("code").asInt()).isZero();
        return node.path("data").path("id").asText();
    }

    private org.springframework.test.web.servlet.ResultActions postJson(String token, String url,
                                                                       Map<String, Object> body)
            throws Exception {
        return mockMvc.perform(post(url)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private String givenToken(String username, int userType, long roleId, List<Long> buildingIds)
            throws Exception {
        SysUser user = new SysUser();
        user.setTenantId(1L);
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setRealName(username);
        user.setUserType(userType);
        user.setStatus(1);
        user.setMustChangePassword(0);
        sysUserMapper.insert(user);
        userIds.add(user.getId());

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

    private String unique(String prefix) {
        // username 列有长度上限；用短前缀 + 进程内序号 + 纳秒尾数，既唯一又不超长
        String shortPrefix = prefix.length() > 12 ? prefix.substring(0, 12) : prefix;
        return shortPrefix + "-" + seq.incrementAndGet() + "-" + (System.nanoTime() % 100000);
    }
}
