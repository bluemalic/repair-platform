package com.bluemalic.repair;

import com.bluemalic.repair.entity.SysUser;
import com.bluemalic.repair.entity.SysUserRole;
import com.bluemalic.repair.mapper.SysUserMapper;
import com.bluemalic.repair.mapper.SysUserRoleMapper;
import com.bluemalic.repair.service.NotificationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 站内通知验收测试：未读数 / 分页 / 标记已读 / 全部已读，以及数据权限——
 * 每个角色只能看到、只能操作自己的通知（handler 的 notification 分支注入 receiver_id）。
 * 通知与权限种子：三个角色都带 notification:read（schema.sql 角色-权限第 5/11/30 条）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class NotificationTest {

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
    private NotificationService notificationService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void unreadCountAndPageAreScopedToCurrentUser() throws Exception {
        String studentA = givenToken("test-notif-a");
        String studentB = givenToken("test-notif-b");

        notificationService.send(1L, userIdOf("test-notif-a"), "TICKET", "接单", "维修工已接单", 100L);
        notificationService.send(1L, userIdOf("test-notif-a"), "TICKET", "到场", "维修工已到场", 100L);
        notificationService.send(1L, userIdOf("test-notif-a"), "TICKET", "完工", "维修工已完工", 100L);
        notificationService.send(1L, userIdOf("test-notif-b"), "TICKET", "接单", "维修工已接单", 101L);

        // A：3 条未读；列表只含自己的 3 条
        JsonNode countA = getJson(studentA, "/api/notifications/unread-count");
        assertThat(countA.get("data").asInt()).isEqualTo(3);

        JsonNode pageA = getJson(studentA, "/api/notifications?pageNum=1&pageSize=10");
        assertThat(pageA.get("data").get("total").asInt()).isEqualTo(3);
        assertThat(pageA.get("data").get("list")).hasSize(3);

        // B：只有自己的 1 条
        JsonNode countB = getJson(studentB, "/api/notifications/unread-count");
        assertThat(countB.get("data").asInt()).isEqualTo(1);

        // isRead 筛选：已读 = 0 条
        JsonNode readPageA = getJson(studentA, "/api/notifications?isRead=1");
        assertThat(readPageA.get("data").get("total").asInt()).isEqualTo(0);
    }

    @Test
    void markReadAndMarkAllReadOtherUsersNotificationIsInvisible() throws Exception {
        String studentA = givenToken("test-notif-a");
        String studentB = givenToken("test-notif-b");

        notificationService.send(1L, userIdOf("test-notif-a"), "TICKET", "接单", "维修工已接单", 100L);
        notificationService.send(1L, userIdOf("test-notif-a"), "TICKET", "到场", "维修工已到场", 100L);

        // 列表第一条是最近一条（create_time DESC, id DESC 决胜）
        JsonNode pageA = getJson(studentA, "/api/notifications");
        String firstId = pageA.get("data").get("list").get(0).get("id").asText();

        // A 标自己的一条 → 未读 2 → 1
        putJson(studentA, "/api/notifications/" + firstId + "/read")
                .andExpect(jsonPath("$.code").value(0));
        JsonNode afterOne = getJson(studentA, "/api/notifications/unread-count");
        assertThat(afterOne.get("data").asInt()).isEqualTo(1);

        // B 标 A 的通知 → 10006：别人的通知 = 不存在（拦截器把 receiver_id = B 注入 UPDATE）
        putJson(studentB, "/api/notifications/" + firstId + "/read")
                .andExpect(jsonPath("$.code").value(10006));

        // A 全部已读 → 0；
        putJson(studentA, "/api/notifications/read-all").andExpect(jsonPath("$.code").value(0));
        JsonNode afterAll = getJson(studentA, "/api/notifications/unread-count");
        assertThat(afterAll.get("data").asInt()).isEqualTo(0);

        // B 本来没有未读，全部已读同样成功（0 行不是错误）
        putJson(studentB, "/api/notifications/read-all").andExpect(jsonPath("$.code").value(0));
    }

    // ==================== 工具 ====================

    private String givenToken(String username) throws Exception {
        SysUser user = new SysUser();
        user.setTenantId(1L);
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setRealName(username);
        user.setUserType(1);
        user.setStatus(1);
        assertThat(sysUserMapper.insert(user)).isEqualTo(1);

        SysUserRole link = new SysUserRole();
        link.setUserId(user.getId());
        link.setRoleId(1L); // 学生角色，含 notification:read
        sysUserRoleMapper.insert(link);

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "tenantCode", "gdou", "username", username, "password", PASSWORD))))
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        JsonNode node = objectMapper.readTree(
                result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        return node.path("data").path("tokenValue").asText();
    }

    private org.springframework.test.web.servlet.ResultActions putJson(String token, String url) throws Exception {
        return mockMvc.perform(put(url).header("Authorization", "Bearer " + token));
    }

    private JsonNode getJson(String token, String url) throws Exception {
        MvcResult result = mockMvc.perform(get(url).header("Authorization", "Bearer " + token)).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private long userIdOf(String username) {
        return sysUserMapper.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<SysUser>lambdaQuery()
                        .eq(SysUser::getUsername, username)).getId();
    }
}