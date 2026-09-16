package com.bluemalic.repair;

import com.bluemalic.repair.entity.SysUser;
import com.bluemalic.repair.entity.SysUserRole;
import com.bluemalic.repair.mapper.SysUserMapper;
import com.bluemalic.repair.mapper.SysUserRoleMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 接口错误契约：**调用方用错接口时，返回哪个 HTTP 状态与业务码**（docs/03 §2.3 的实现守卫）。
 *
 * <p>集中在一个类里，是因为这些断言跨接口、跨业务域，不属于任何一个业务模块；塞进某个业务测试类
 * 会让人误以为它只跟那个接口有关。
 *
 * <p>这五条的共同点：**都是调用方的问题，不是服务端故障**。它们曾经（或差点）全部落到兜底的
 * {@code Exception} 处理器上，表现成 500 + {@code 10005 系统繁忙} + 一整段 ERROR 堆栈——
 * 把调用方直接引向"服务端崩了"的错误排查方向，而真正的原因就在他刚发的那条请求里。
 *
 * <p>另外两条容易被误改的约定：
 * <ul>
 *   <li>参数类问题走 **HTTP 200 + `10001`**（与 {@code @Valid} 失败同口径），只有"路径/方法/请求体类型
 *       根本对不上"才用 404 / 405 / 415，因为那三种请求压根没进到业务里</li>
 *   <li>报错信息**只带参数名、不带参数值**，也不回显请求体原文——值可能是敏感信息，写进日志或
 *       回给前端就收不回来了</li>
 * </ul>
 */
@IntegrationTest
class WebErrorContractTest {

    private static final String PASSWORD = "Test@123456";
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
    private PasswordEncoder passwordEncoder;

    @Test
    void wrongHttpMethodIs405NotServerError() throws Exception {
        // 浏览器把接口地址粘进地址栏就是这个样子：它只会发 GET，而登录是 POST
        mockMvc.perform(get("/api/auth/login"))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(jsonPath("$.code").value(10006));
    }

    @Test
    void unsupportedContentTypeIs415NotServerError() throws Exception {
        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.TEXT_PLAIN).content("not json"))
                .andExpect(status().isUnsupportedMediaType())
                .andExpect(jsonPath("$.code").value(10006));
    }

    @Test
    void missingRequiredParamIsParamInvalid() throws Exception {
        // /distribution 的 dimension 是唯一一个必填 query 参数（其余都有默认值或标了可空）
        String admin = givenAdminToken("test-error-missing-param");

        mockMvc.perform(get("/api/admin/statistics/distribution")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.message").value("缺少必填参数 dimension"));
    }

    @Test
    void wrongParamTypeIsParamInvalid() throws Exception {
        String admin = givenAdminToken("test-error-param-type");

        // start 是 LocalDate：给一个转不过去的值，属于"参数有问题"，不是"服务端繁忙"
        mockMvc.perform(get("/api/admin/statistics/overview")
                        .param("start", "not-a-date")
                        .header("Authorization", "Bearer " + admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.message").value("参数 start 类型不正确"));
    }

    @Test
    void unreadableBodyIsParamInvalid() throws Exception {
        // 空请求体 / 不是合法 JSON：报错信息里不能回显请求体原文（可能含密码）
        mockMvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.message").value("请求体缺失或不是合法 JSON"));
    }

    // ==================== 工具 ====================

    /** 造后勤管理用户并登录拿 token（统计接口要求已登录；租户 1 与角色 3 都是建表脚本的种子数据）。 */
    private String givenAdminToken(String username) throws Exception {
        SysUser user = new SysUser();
        user.setTenantId(1L);
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setRealName("测试用户");
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
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path("tokenValue").asText();
    }
}
