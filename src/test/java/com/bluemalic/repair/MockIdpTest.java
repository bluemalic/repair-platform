package com.bluemalic.repair;

import com.bluemalic.repair.sso.SsoStateStore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 演示用的 Mock IdP（阶段 7 · ADR-022）。
 *
 * <p>它存在的意义是"让统一认证这条链路可以本地跑通、演示站可以点通"，所以测试要验的是
 * **它与真实协议一致的那部分**：授权码一次性、PKCE 真的校验、ticket 一次性、userinfo 要 Bearer。
 * 这些一旦写错，适配器在真实 IdP 上也会错——但错误会被误判成"学校 IdP 有问题"。
 *
 * <p>测试上下文里 Mock IdP 是打开的（`@IntegrationTest` 的 properties）；
 * 生产默认关闭，关闭时所有端点 404（见 {@code MockIdpController} 的类注释）。
 */
@IntegrationTest
class MockIdpTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void oauth2的Mock链路_授权码一次性_PKCE真的校验() throws Exception {
        String verifier = "verifier-for-mock";
        String challenge = SsoStateStore.codeChallenge(verifier);

        // ① 授权页：列出演示身份
        mockMvc.perform(get("/api/mock-idp/oauth2/authorize")
                        .param("client_id", "c1")
                        .param("redirect_uri", "http://app.test/cb")
                        .param("state", "state-1")
                        .param("code_challenge", challenge))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("name=\"subject\"")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("20260001")));

        // ② 点身份 → 回调地址带 code 与 state
        MvcResult approve = mockMvc.perform(post("/api/mock-idp/oauth2/approve")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("subject", "20260001")
                        .param("redirect_uri", "http://app.test/cb")
                        .param("state", "state-1")
                        .param("code_challenge", challenge))
                .andExpect(status().isFound()).andReturn();
        String location = approve.getResponse().getHeader("Location");
        assertThat(location).startsWith("http://app.test/cb?code=").contains("state=state-1");
        String code = parseQuery(location).get("code");

        // ③ 用 code + verifier 换 token
        MvcResult tokenResult = mockMvc.perform(post("/api/mock-idp/oauth2/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "authorization_code")
                        .param("code", code)
                        .param("redirect_uri", "http://app.test/cb")
                        .param("client_id", "c1")
                        .param("code_verifier", verifier))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token_type").value("Bearer"))
                .andReturn();
        String accessToken = json(tokenResult).path("access_token").asText();

        // ④ userinfo 要 Bearer，返回登录名
        mockMvc.perform(get("/api/mock-idp/oauth2/userinfo")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("20260001"));
        mockMvc.perform(get("/api/mock-idp/oauth2/userinfo"))
                .andExpect(status().isUnauthorized());

        // ⑤ 授权码一次性：同一个 code 再换一次 → invalid_grant
        mockMvc.perform(post("/api/mock-idp/oauth2/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "authorization_code")
                        .param("code", code)
                        .param("redirect_uri", "http://app.test/cb")
                        .param("client_id", "c1")
                        .param("code_verifier", verifier))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));
    }

    @Test
    void oauth2的PKCE对不上就拒绝换token() throws Exception {
        String challenge = SsoStateStore.codeChallenge("right-verifier");
        MvcResult approve = mockMvc.perform(post("/api/mock-idp/oauth2/approve")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("subject", "20260001")
                        .param("redirect_uri", "http://app.test/cb")
                        .param("code_challenge", challenge))
                .andExpect(status().isFound()).andReturn();
        String code = parseQuery(approve.getResponse().getHeader("Location")).get("code");

        mockMvc.perform(post("/api/mock-idp/oauth2/token")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "authorization_code")
                        .param("code", code)
                        .param("redirect_uri", "http://app.test/cb")
                        .param("client_id", "c1")
                        .param("code_verifier", "wrong-verifier"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void cas的Mock链路_ticket一次性() throws Exception {
        mockMvc.perform(get("/api/mock-idp/cas/login")
                        .param("service", "http://app.test/cb?state=state-1"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("worker01")));

        MvcResult approve = mockMvc.perform(post("/api/mock-idp/cas/approve")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("subject", "worker01")
                        .param("service", "http://app.test/cb?state=state-1"))
                .andExpect(status().isFound()).andReturn();
        String location = approve.getResponse().getHeader("Location");
        assertThat(location).startsWith("http://app.test/cb?state=state-1").contains("ticket=ST-");
        String ticket = parseQuery(location).get("ticket");

        mockMvc.perform(get("/api/mock-idp/cas/validate")
                        .param("ticket", ticket).param("service", "http://app.test/cb?state=state-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serviceResponse.authenticationSuccess.user").value("worker01"));

        // 一次性：再校验一次就是 authenticationFailure（与真实 CAS 一致）
        mockMvc.perform(get("/api/mock-idp/cas/validate")
                        .param("ticket", ticket).param("service", "http://app.test/cb?state=state-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serviceResponse.authenticationFailure.code").value("INVALID_TICKET"));
    }

    // ==================== 工具 ====================

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    private Map<String, String> parseQuery(String url) {
        Map<String, String> result = new HashMap<>();
        String query = URI.create(url).getQuery();
        if (query == null) {
            return result;
        }
        for (String pair : query.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                result.put(java.net.URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return result;
    }
}
