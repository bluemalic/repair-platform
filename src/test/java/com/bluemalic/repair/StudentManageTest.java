package com.bluemalic.repair;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bluemalic.repair.common.TicketStatus;
import com.bluemalic.repair.common.UserType;
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
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 学生账号管理 + 首次登录强制改密。
 *
 * <p>这个类里最值钱的是**强制改密那两条**：它守的是一个"看起来生效了其实没有"的功能——
 * 前端跳改密页只是引导，绕过它照样能调接口。要真的成立，服务端必须也拦一道。
 *
 * <p>其次是**批量导入的幂等**：学校补录学生时，名单里必然带着上次已导的人。如果重复导入报错，
 * 这个功能在真实场景里就没法用；如果重复导入建出两个账号，那就是数据事故。
 *
 * <p>其余是管理类接口的常规检查（照 {@code WorkerManageTest}）：建号要能登录且拿到角色权限、
 * 停用要能踢下线、权限码要真的挡住别的角色。
 */
@IntegrationTest
class StudentManageTest {

    private static final String TENANT_CODE = "gdou";
    private static final String PASSWORD = "Test@123456";
    private static final String INITIAL_PASSWORD = "Init@123456";
    private static final String CHANGED_PASSWORD = "Changed@9876";
    private static final long ROLE_STUDENT = 1L;
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
    private TicketMapper ticketMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    // ==================== 建号 ====================

    @Test
    void createdStudentCanLoginAndCarriesRole() throws Exception {
        String admin = givenAdminToken();

        postJson(admin, "/api/admin/students", Map.of(
                "username", "test-stu-1", "realName", "张三", "password", INITIAL_PASSWORD))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.mustChangePassword").value(true));

        // 能登录，而且角色/权限都拿得到 —— 这条同时证明 sys_user_role 关联插对了。
        // 只断言"接口返回 0"是不够的：漏插角色关联的账号照样能建出来、能登录，只是每个接口都 403
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer "
                        + login("test-stu-1", INITIAL_PASSWORD)))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.roles[0]").value("STUDENT"))
                .andExpect(jsonPath("$.data.permissions").isNotEmpty());
    }

    @Test
    void duplicateUsernameIsRejected() throws Exception {
        String admin = givenAdminToken();
        postJson(admin, "/api/admin/students", Map.of(
                "username", "test-stu-dup", "realName", "李四", "password", INITIAL_PASSWORD))
                .andExpect(jsonPath("$.code").value(0));

        postJson(admin, "/api/admin/students", Map.of(
                "username", "test-stu-dup", "realName", "王五", "password", INITIAL_PASSWORD))
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.message").value("学号已存在"));
    }

    // ==================== 首次登录强制改密 ====================

    @Test
    void mustChangePasswordBlocksBusinessApiUntilChanged() throws Exception {
        String admin = givenAdminToken();
        postJson(admin, "/api/admin/students", Map.of(
                "username", "test-stu-force", "realName", "赵六", "password", INITIAL_PASSWORD))
                .andExpect(jsonPath("$.code").value(0));

        String token = login("test-stu-force", INITIAL_PASSWORD);

        // ① 登录时响应里带了标记，前端据此跳改密页
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0));

        // ② 业务接口被拦下 —— 这是"强制"真正成立的地方
        mockMvc.perform(get("/api/student/tickets").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(10003))
                .andExpect(jsonPath("$.message").value("请先修改初始口令"));

        // ③ 改密接口本身必须放行，否则用户卡在"要改密才能用、但改密也调不动"的死循环里
        mockMvc.perform(put("/api/auth/password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "oldPassword", INITIAL_PASSWORD, "newPassword", CHANGED_PASSWORD))))
                .andExpect(jsonPath("$.code").value(0));

        // ④ 改完重新登录，业务接口就通了
        String newToken = login("test-stu-force", CHANGED_PASSWORD);
        mockMvc.perform(get("/api/student/tickets").header("Authorization", "Bearer " + newToken))
                .andExpect(jsonPath("$.code").value(0));

        // ⑤ 标记在库里也被清掉了（不是只在这一次会话里失效）
        assertThat(userBy("test-stu-force").getMustChangePassword()).isZero();
    }

    @Test
    void adminResetPasswordPutsAccountBackToMustChange() throws Exception {
        String admin = givenAdminToken();
        postJson(admin, "/api/admin/students", Map.of(
                "username", "test-stu-reset", "realName", "孙七", "password", INITIAL_PASSWORD))
                .andExpect(jsonPath("$.code").value(0));
        long id = userBy("test-stu-reset").getId();

        // 先把标记清掉（模拟学生已经改过密）
        mockMvc.perform(put("/api/auth/password")
                        .header("Authorization", "Bearer " + login("test-stu-reset", INITIAL_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "oldPassword", INITIAL_PASSWORD, "newPassword", CHANGED_PASSWORD))))
                .andExpect(jsonPath("$.code").value(0));
        assertThat(userBy("test-stu-reset").getMustChangePassword()).isZero();

        // 管理员重置口令：必须回到"首登改密"状态——重置出来的口令同样是一次性的
        putJson(admin, "/api/admin/students/" + id + "/password", Map.of("password", "Reset@123456"))
                .andExpect(jsonPath("$.code").value(0));
        assertThat(userBy("test-stu-reset").getMustChangePassword()).isEqualTo(1);

        String token = login("test-stu-reset", "Reset@123456");
        mockMvc.perform(get("/api/student/tickets").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(10003));
    }

    // ==================== 批量导入 ====================

    @Test
    void importIsIdempotentAndParsesNames() throws Exception {
        String admin = givenAdminToken();
        String rows = """
                20260003,张三
                20260004

                20260005,李四
                20260003,张三（重复行）
                """;

        MvcResult first = postJson(admin, "/api/admin/students/import",
                Map.of("rows", rows, "password", INITIAL_PASSWORD))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        JsonNode firstBody = readJson(first).path("data");
        // 3 个新学号；1 个空行 + 1 个批次内重复行被忽略
        assertThat(firstBody.path("created").asInt()).isEqualTo(3);
        assertThat(firstBody.path("skipped").asInt()).isZero();
        assertThat(firstBody.path("ignored").asInt()).isEqualTo(2);

        // 姓名列被吃进去了
        assertThat(userBy("20260003").getRealName()).isEqualTo("张三");

        // 再导一次同一份名单：**不报错、不重复建**，全部记为跳过
        MvcResult second = postJson(admin, "/api/admin/students/import",
                Map.of("rows", rows, "password", INITIAL_PASSWORD))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        JsonNode secondBody = readJson(second).path("data");
        assertThat(secondBody.path("created").asInt()).isZero();
        assertThat(secondBody.path("skipped").asInt()).isEqualTo(3);
        assertThat(secondBody.path("skippedUsernames").size()).isEqualTo(3);

        // 库里确实只有一个 20260003
        assertThat(sysUserMapper.selectCount(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getTenantId, 1L)
                .eq(SysUser::getUsername, "20260003"))).isEqualTo(1);
    }

    @Test
    void importRejectsOversizedBatch() throws Exception {
        String admin = givenAdminToken();
        StringBuilder rows = new StringBuilder();
        for (int i = 0; i < 501; i++) {
            rows.append("test-bulk-").append(i).append("\n");
        }

        postJson(admin, "/api/admin/students/import",
                Map.of("rows", rows.toString(), "password", INITIAL_PASSWORD))
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("单次最多导入 500 条")));
    }

    // ==================== 与既有能力的配合 ====================

    @Test
    void disabledStudentIsKickedOutImmediately() throws Exception {
        String admin = givenAdminToken();
        postJson(admin, "/api/admin/students", Map.of(
                "username", "test-stu-disable", "realName", "周八", "password", INITIAL_PASSWORD))
                .andExpect(jsonPath("$.code").value(0));
        long id = userBy("test-stu-disable").getId();

        // 先改密以解开强制改密的拦截，否则后续断言会被 10003 干扰
        mockMvc.perform(put("/api/auth/password")
                        .header("Authorization", "Bearer " + login("test-stu-disable", INITIAL_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "oldPassword", INITIAL_PASSWORD, "newPassword", CHANGED_PASSWORD))))
                .andExpect(jsonPath("$.code").value(0));
        String token = login("test-stu-disable", CHANGED_PASSWORD);
        mockMvc.perform(get("/api/student/tickets").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0));

        putJson(admin, "/api/admin/students/" + id, Map.of(
                "realName", "周八", "status", 0)).andExpect(jsonPath("$.code").value(0));

        // 停用后旧 token 立刻失效：登录态在 Redis，光改 sys_user.status 是挡不住的
        mockMvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(10002));
    }

    @Test
    void studentSeesOnlyOwnTickets() throws Exception {
        String admin = givenAdminToken();
        postJson(admin, "/api/admin/students", Map.of(
                "username", "test-stu-scope", "realName", "吴九", "password", INITIAL_PASSWORD))
                .andExpect(jsonPath("$.code").value(0));
        long studentId = userBy("test-stu-scope").getId();

        // 造两条工单：一条是他的、一条是别人的
        givenTicket(studentId, "test-stu-scope-room");
        givenTicket(999999L, "test-other-room");

        mockMvc.perform(put("/api/auth/password")
                        .header("Authorization", "Bearer " + login("test-stu-scope", INITIAL_PASSWORD))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "oldPassword", INITIAL_PASSWORD, "newPassword", CHANGED_PASSWORD))))
                .andExpect(jsonPath("$.code").value(0));

        String token = login("test-stu-scope", CHANGED_PASSWORD);
        MvcResult result = mockMvc.perform(get("/api/student/tickets")
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        JsonNode list = readJson(result).path("data").path("list");
        assertThat(list.size()).isEqualTo(1);
        assertThat(list.get(0).path("room").asText()).isEqualTo("test-stu-scope-room");
    }

    @Test
    void studentCannotCallAdminApi() throws Exception {
        String student = givenToken("test-stu-forbidden", UserType.STUDENT.getCode(), ROLE_STUDENT);

        // 权限码 student:manage 只授给后勤：数据权限管"能看哪些数据"，功能权限管"能不能调这个接口"
        mockMvc.perform(get("/api/admin/students").header("Authorization", "Bearer " + student))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(10003));
    }

    // ==================== 工具 ====================

    /** 测试事务会回滚，所以用户名用固定值即可（不需要拼时间戳防撞——那反而会引入不确定性）。 */
    private String givenAdminToken() throws Exception {
        return givenToken("test-stu-admin", UserType.ADMIN.getCode(), ROLE_ADMIN);
    }

    /** 造用户 + 角色并登录拿 token（租户 1 = 种子数据 gdou）。 */
    private String givenToken(String username, int userType, long roleId) throws Exception {
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

        return login(username, PASSWORD);
    }

    private void givenTicket(long studentId, String room) {
        Ticket ticket = new Ticket();
        ticket.setTenantId(1L);
        ticket.setTicketNo("WX-TEST-" + System.nanoTime());
        ticket.setStudentId(studentId);
        ticket.setBuildingId(1L);
        ticket.setRoom(room);
        ticket.setCategoryId(1L);
        ticket.setDescription("测试工单");
        ticket.setStatus(TicketStatus.TO_DISPATCH.getCode());
        ticket.setSubmitTime(LocalDateTime.now());
        ticketMapper.insert(ticket);
    }

    private SysUser userBy(String username) {
        SysUser user = sysUserMapper.selectOne(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getTenantId, 1L)
                .eq(SysUser::getUsername, username));
        assertThat(user).as("账号 %s 应存在", username).isNotNull();
        return user;
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "tenantCode", TENANT_CODE, "username", username, "password", password))))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        return readJson(result).path("data").path("tokenValue").asText();
    }

    private org.springframework.test.web.servlet.ResultActions postJson(String token, String url, Map<String, ?> body)
            throws Exception {
        return mockMvc.perform(post(url)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private org.springframework.test.web.servlet.ResultActions putJson(String token, String url, Map<String, ?> body)
            throws Exception {
        return mockMvc.perform(put(url)
                .header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(body)));
    }

    private JsonNode readJson(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
