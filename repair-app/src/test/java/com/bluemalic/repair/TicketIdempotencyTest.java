package com.bluemalic.repair;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bluemalic.repair.entity.SysUser;
import com.bluemalic.repair.entity.SysUserRole;
import com.bluemalic.repair.entity.Ticket;
import com.bluemalic.repair.mapper.SysUserMapper;
import com.bluemalic.repair.mapper.SysUserRoleMapper;
import com.bluemalic.repair.mapper.TicketMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 提交报修的幂等键（阶段 2）：同一个学生用同一个 key 提交，只会产生一张工单。
 *
 * <p>学生端连点两次、或网络超时后重试，是真实会发生的事；没有幂等键就是两张单，
 * 维修工会被派两次。这里钉住四件事：同 key 返回同一张、不同 key 各自成单、
 * 不同学生用同一个 key 互不影响、缺 key 直接拒绝。
 */
@IntegrationTest
class TicketIdempotencyTest {

    private static final String PASSWORD = "Test@123456";
    private static final long ROLE_STUDENT = 1L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private SysUserRoleMapper sysUserRoleMapper;

    @Autowired
    private TicketMapper ticketMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void sameKeyReturnsTheSameTicket() throws Exception {
        String student = givenStudent("test-idem-student-1");
        String key = "test-idem-" + System.nanoTime();

        String firstId = submit(student, key, "第一次提交").path("data").path("id").asText();
        String secondId = submit(student, key, "重试时描述被改过").path("data").path("id").asText();

        assertThat(secondId).as("同一个 key 必须返回同一张工单，而不是新建").isEqualTo(firstId);
        assertThat(countByKey(key)).as("数据库里只能有一行").isEqualTo(1);
        assertThat(ticketById(firstId).getDescription()).as("内容以第一次为准，重试不会覆盖").isEqualTo("第一次提交");
    }

    @Test
    void differentKeysCreateSeparateTickets() throws Exception {
        String student = givenStudent("test-idem-student-2");
        String prefix = "test-idem-" + System.nanoTime();

        String firstId = submit(student, prefix + "-a", "第一张").path("data").path("id").asText();
        String secondId = submit(student, prefix + "-b", "第二张").path("data").path("id").asText();

        assertThat(secondId).as("不同的提交意图应当各自成单").isNotEqualTo(firstId);
        assertThat(countByKey(prefix + "-a")).isEqualTo(1);
        assertThat(countByKey(prefix + "-b")).isEqualTo(1);
    }

    /**
     * 唯一键是 (tenant_id, student_id, key)：两个学生各自生成 UUID 时撞车也不该互相挡住。
     */
    @Test
    void sameKeyFromAnotherStudentDoesNotCollide() throws Exception {
        String studentA = givenStudent("test-idem-student-3a");
        String studentB = givenStudent("test-idem-student-3b");
        String key = "test-idem-" + System.nanoTime();

        String idA = submit(studentA, key, "A 的报修").path("data").path("id").asText();
        String idB = submit(studentB, key, "B 的报修").path("data").path("id").asText();

        assertThat(idB).as("幂等域是学生自己，不是全局").isNotEqualTo(idA);
        assertThat(countByKey(key)).isEqualTo(2);
    }

    @Test
    void missingKeyIsRejectedAndCreatesNothing() throws Exception {
        String student = givenStudent("test-idem-student-4");
        String description = "缺 key-" + System.nanoTime();

        Map<String, Object> body = new HashMap<>();
        body.put("repairCode", "482913");
        body.put("categoryId", 1);
        body.put("description", description);
        postJson(student, "/api/student/tickets", body)
                .andExpect(jsonPath("$.code").value(10001));

        assertThat(ticketMapper.selectCount(Wrappers.<Ticket>lambdaQuery()
                .eq(Ticket::getDescription, description)))
                .as("参数校验失败时不能落单").isZero();
    }

    private JsonNode submit(String token, String key, String description) throws Exception {
        MvcResult result = postJson(token, "/api/student/tickets", Map.of(
                        "idempotencyKey", key,
                        "repairCode", "482913",
                        "categoryId", 1,
                        "description", description))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private Ticket ticketById(String id) {
        return ticketMapper.selectById(Long.parseLong(id));
    }

    private long countByKey(String key) {
        return ticketMapper.selectCount(Wrappers.<Ticket>lambdaQuery()
                .eq(Ticket::getIdempotencyKey, key));
    }

    /** 造学生账号并登录拿 token（种子租户 gdou，与其它工单用例同一套夹具）。 */
    private String givenStudent(String username) throws Exception {
        SysUser user = new SysUser();
        user.setTenantId(1L);
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setRealName(username);
        user.setUserType(1);
        user.setStatus(1);
        user.setMustChangePassword(0);
        sysUserMapper.insert(user);

        SysUserRole link = new SysUserRole();
        link.setUserId(user.getId());
        link.setRoleId(ROLE_STUDENT);
        sysUserRoleMapper.insert(link);

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "tenantCode", "gdou", "username", username, "password", PASSWORD))))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path("tokenValue").asText();
    }

    private org.springframework.test.web.servlet.ResultActions postJson(String token, String url,
                                                                       Map<String, Object> body)
            throws Exception {
        return mockMvc.perform(post(url)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }
}
