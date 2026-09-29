package com.bluemalic.repair;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bluemalic.repair.entity.SysUser;
import com.bluemalic.repair.entity.SysUserRole;
import com.bluemalic.repair.entity.Ticket;
import com.bluemalic.repair.entity.TicketCategory;
import com.bluemalic.repair.entity.TicketLog;
import com.bluemalic.repair.entity.WorkerBuilding;
import com.bluemalic.repair.mapper.SysUserMapper;
import com.bluemalic.repair.mapper.SysUserRoleMapper;
import com.bluemalic.repair.mapper.TicketCategoryMapper;
import com.bluemalic.repair.mapper.TicketLogMapper;
import com.bluemalic.repair.mapper.TicketMapper;
import com.bluemalic.repair.mapper.WorkerBuildingMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 拆单（一张单里其实是两件事，`docs/01` §4.5）。
 *
 * <p>拆单最容易做错的地方是**把原单也动了**（顺手改描述、顺手把师傅带走、顺手重置计时），
 * 所以断言盯的是"原单一个字都没变"：状态、描述、主责、未接单提醒的到期点都不变——
 * 拆出来的是一张**新单**，它自己的活自己走。
 *
 * <p>另外两条边界：只拆一层（拆出来的单不能再拆）、40 之后不能拆（活都干完了）。
 */
@IntegrationTest
class TicketSplitTest {

    private static final String PASSWORD = "Test@123456";
    private static final long ROLE_STUDENT = 1L;
    private static final long ROLE_WORKER = 2L;
    private static final long ROLE_ADMIN = 3L;
    private static final String ACCEPT_NODE = "ticket:timeout:ACCEPT";

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
    private TicketLogMapper ticketLogMapper;

    @Autowired
    private TicketCategoryMapper ticketCategoryMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private final List<String> createdTickets = new ArrayList<>();

    /** 与 {@code TicketCollaboratorTest} 同一个理由：Redis 不跟着事务回滚，别给后来的人留垃圾。 */
    @AfterEach
    void cleanupTimeoutMembers() {
        if (createdTickets.isEmpty()) {
            return;
        }
        Object[] ids = createdTickets.toArray();
        for (String node : List.of("ACCEPT", "PROCESS", "EVAL")) {
            stringRedisTemplate.opsForZSet().remove("ticket:timeout:" + node, ids);
        }
    }

    @Test
    void splitCreatesPendingTicketInheritingLocationStudentAndImages() throws Exception {
        String admin = givenToken("test-sp-admin", 3, ROLE_ADMIN, null);
        String student = givenToken("test-sp-student", 1, ROLE_STUDENT, null);
        String sourceId = submittedTicket(student, List.of("http://example.test/on-site.jpg"));
        Ticket source = ticketOf(sourceId);

        MvcResult result = postJson(admin, "/api/admin/tickets/" + sourceId + "/split", Map.of(
                        "description", "还有一个水龙头在漏水", "categoryId", 2, "urgency", 3))
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        JsonNode created = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data");
        String newId = created.path("id").asText();
        createdTickets.add(newId);

        // 新单：待派单（10），继承"在哪、谁报的、现场照片"，描述与类别/紧急度按传进来的走
        Ticket split = ticketOf(newId);
        assertThat(split.getStatus()).isEqualTo(10);
        assertThat(split.getWorkerId()).isNull();
        assertThat(split.getParentTicketId()).isEqualTo(Long.parseLong(sourceId));
        assertThat(split.getStudentId()).isEqualTo(source.getStudentId());
        assertThat(split.getBuildingId()).isEqualTo(source.getBuildingId());
        assertThat(split.getRoom()).isEqualTo(source.getRoom());
        assertThat(split.getImages()).containsExactly("http://example.test/on-site.jpg");
        assertThat(split.getDescription()).isEqualTo("还有一个水龙头在漏水");
        assertThat(split.getCategoryId()).isEqualTo(2L);
        assertThat(split.getUrgency()).isEqualTo(3);
        assertThat(split.getTicketNo()).isNotEqualTo(source.getTicketNo());
        // 返回体直接带新单号，前端不用再查一次
        assertThat(created.path("ticketNo").asText()).isEqualTo(split.getTicketNo());

        // **原单一个字都没变**
        Ticket reloaded = ticketOf(sourceId);
        assertThat(reloaded.getStatus()).isEqualTo(10);
        assertThat(reloaded.getDescription()).isEqualTo(source.getDescription());
        assertThat(reloaded.getCategoryId()).isEqualTo(source.getCategoryId());
        assertThat(reloaded.getUrgency()).isEqualTo(source.getUrgency());

        // 两张单各记一条 SPLIT：原单说"我拆出了谁"，新单说"我从哪来"——各自的时间线都能自己解释自己
        assertThat(logsOf(sourceId)).extracting(TicketLog::getAction).containsExactly("SUBMIT", "SPLIT");
        assertThat(logsOf(sourceId).get(1).getRemark()).contains(split.getTicketNo());
        assertThat(logsOf(newId)).extracting(TicketLog::getAction).containsExactly("SPLIT");
        assertThat(logsOf(newId).get(0).getRemark()).contains(source.getTicketNo());

        // 通知学生（他报的一单变成了两张）
        assertThat(notificationTitles(student)).contains("报修已拆成两张单");

        // 新单是一张普通的待派单工单：派单、接单照常走
        String worker = givenToken("test-sp-worker", 2, ROLE_WORKER, List.of(1L));
        postJson(admin, "/api/admin/tickets/" + newId + "/dispatch",
                Map.of("workerId", userIdOf("test-sp-worker"))).andExpect(jsonPath("$.code").value(0));
        assertThat(ticketOf(newId).getStatus()).isEqualTo(20);
        assertThat(worker).isNotBlank();
    }

    @Test
    void splitInheritsCategoryAndUrgencyWhenNotGiven() throws Exception {
        String admin = givenToken("test-sp2-admin", 3, ROLE_ADMIN, null);
        String student = givenToken("test-sp2-student", 1, ROLE_STUDENT, null);
        String sourceId = submittedTicket(student, List.of());
        Ticket source = ticketOf(sourceId);

        String newId = splitTicket(admin, sourceId, Map.of("description", "另一件事"));

        Ticket split = ticketOf(newId);
        assertThat(split.getCategoryId()).isEqualTo(source.getCategoryId());
        assertThat(split.getUrgency()).isEqualTo(source.getUrgency());

        // 学生端看得到两张单（原来那张还在，新那张也归他）
        List<String> mine = listIds(student, "/api/student/tickets");
        assertThat(mine).contains(sourceId, newId);
    }

    @Test
    void splitWhileProcessingKeepsSourceUntouchedAndNotifiesOwner() throws Exception {
        String admin = givenToken("test-sp3-admin", 3, ROLE_ADMIN, null);
        String student = givenToken("test-sp3-student", 1, ROLE_STUDENT, null);
        String worker = givenToken("test-sp3-worker", 2, ROLE_WORKER, List.of(1L));
        String sourceId = submittedTicket(student, List.of());
        postJson(admin, "/api/admin/tickets/" + sourceId + "/dispatch",
                Map.of("workerId", userIdOf("test-sp3-worker")));
        createdTickets.add(sourceId);
        Double beforeScore = stringRedisTemplate.opsForZSet().score(ACCEPT_NODE, sourceId);

        String newId = splitTicket(admin, sourceId, Map.of("description", "顺带还有一处要修"));

        // 原单：状态、主责、**未接单提醒的到期点**都不变——拆单不是重新派单，计时不动
        Ticket source = ticketOf(sourceId);
        assertThat(source.getStatus()).isEqualTo(20);
        assertThat(source.getWorkerId()).isEqualTo(userIdOf("test-sp3-worker"));
        assertThat(stringRedisTemplate.opsForZSet().score(ACCEPT_NODE, sourceId)).isEqualTo(beforeScore);

        // 主责要知道自己那一半活被拆走了
        assertThat(notificationTitles(worker)).contains("你的工单已拆出一部分");
        assertThat(ticketOf(newId).getParentTicketId()).isEqualTo(Long.parseLong(sourceId));
    }

    @Test
    void splitIsRejectedAtFinishedStatusAndOnSplitTicket() throws Exception {
        String admin = givenToken("test-sp4-admin", 3, ROLE_ADMIN, null);
        String student = givenToken("test-sp4-student", 1, ROLE_STUDENT, null);
        String worker = givenToken("test-sp4-worker", 2, ROLE_WORKER, List.of(1L));
        String sourceId = submittedTicket(student, List.of());
        postJson(admin, "/api/admin/tickets/" + sourceId + "/dispatch",
                Map.of("workerId", userIdOf("test-sp4-worker")));
        createdTickets.add(sourceId);

        // 只拆一层：拆出来的单不能再拆（否则会变成一棵谁都说不清的树）
        String newId = splitTicket(admin, sourceId, Map.of("description", "拆出来的那一件"));
        postJson(admin, "/api/admin/tickets/" + newId + "/split", Map.of("description", "再拆一层"))
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.message", org.hamcrest.Matchers.containsString("不能再拆")));

        // 完工之后（40）活已经干完，"拆"没有意义
        postJson(worker, "/api/worker/tickets/" + sourceId + "/accept", Map.of());
        postJson(worker, "/api/worker/tickets/" + sourceId + "/arrive", Map.of("repairCode", "482913"));
        postJson(worker, "/api/worker/tickets/" + sourceId + "/finish",
                Map.of("resultDesc", "修好了", "resultImages", List.of()))
                .andExpect(jsonPath("$.code").value(0));
        postJson(admin, "/api/admin/tickets/" + sourceId + "/split", Map.of("description", "拆一下"))
                .andExpect(jsonPath("$.code").value(20002));
    }

    @Test
    void splitValidatesDescriptionCategoryAndPermission() throws Exception {
        String admin = givenToken("test-sp5-admin", 3, ROLE_ADMIN, null);
        String student = givenToken("test-sp5-student", 1, ROLE_STUDENT, null);
        String worker = givenToken("test-sp5-worker", 2, ROLE_WORKER, List.of(1L));
        String sourceId = submittedTicket(student, List.of());

        // 描述必填：拆出来那件事得自己说清楚，否则两张单长得一样，等于没拆
        postJson(admin, "/api/admin/tickets/" + sourceId + "/split", Map.of("description", "   "))
                .andExpect(jsonPath("$.code").value(10001));
        // 不存在的类别
        postJson(admin, "/api/admin/tickets/" + sourceId + "/split",
                Map.of("description", "另一件事", "categoryId", 99999999L))
                .andExpect(jsonPath("$.code").value(10001));
        // 已停用的类别（本租户）
        long disabledId = givenCategory(1L, "停用类别", 0);
        postJson(admin, "/api/admin/tickets/" + sourceId + "/split",
                Map.of("description", "另一件事", "categoryId", disabledId))
                .andExpect(jsonPath("$.code").value(10001));
        // **别家租户的类别**：不能拿来建单，也不能暴露"这个类别存在"
        long foreignId = givenCategory(2L, "别家类别", 1);
        postJson(admin, "/api/admin/tickets/" + sourceId + "/split",
                Map.of("description", "另一件事", "categoryId", foreignId))
                .andExpect(jsonPath("$.code").value(10001));

        // 权限：拆单是调度动作，学生与维修工都不行
        for (String token : List.of(student, worker)) {
            mockMvc.perform(post("/api/admin/tickets/" + sourceId + "/split")
                            .header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(objectMapper.writeValueAsString(Map.of("description", "拆一下"))))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(10003));
        }
    }

    // ==================== 工具 ====================

    private String submittedTicket(String student, List<String> images) throws Exception {
        MvcResult result = postJson(student, "/api/student/tickets", Map.of(
                        "repairCode", "482913", "categoryId", 1, "description", "【测试】灯坏了，顺便水龙头也漏",
                        "images", images))
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        String ticketId = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path("id").asText();
        createdTickets.add(ticketId);
        return ticketId;
    }

    private String splitTicket(String admin, String sourceId, Map<String, Object> body) throws Exception {
        MvcResult result = postJson(admin, "/api/admin/tickets/" + sourceId + "/split", body)
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        String newId = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path("id").asText();
        createdTickets.add(newId);
        return newId;
    }

    private long givenCategory(long tenantId, String name, int status) {
        TicketCategory category = new TicketCategory();
        category.setTenantId(tenantId);
        category.setName(name);
        category.setSort(99);
        category.setStatus(status);
        ticketCategoryMapper.insert(category);
        return category.getId();
    }

    private Ticket ticketOf(String ticketId) {
        return ticketMapper.selectById(Long.parseLong(ticketId));
    }

    private List<TicketLog> logsOf(String ticketId) {
        return ticketLogMapper.selectList(Wrappers.<TicketLog>lambdaQuery()
                .eq(TicketLog::getTicketId, Long.parseLong(ticketId))
                .orderByAsc(TicketLog::getCreateTime));
    }

    private List<String> notificationTitles(String token) throws Exception {
        return java.util.stream.StreamSupport
                .stream(getJson(token, "/api/notifications?pageNum=1&pageSize=10").path("data").path("list")
                        .spliterator(), false)
                .map(node -> node.path("title").asText()).toList();
    }

    private List<String> listIds(String token, String url) throws Exception {
        return java.util.stream.StreamSupport
                .stream(getJson(token, url).path("data").path("list").spliterator(), false)
                .map(node -> node.path("id").asText()).toList();
    }

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
