package com.bluemalic.repair.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 网关登录校验的两条硬约定：
 * <ol>
 *   <li>{@code /api/**} 没有 token 时返回业务码 10002（HTTP 200 + JSON，与单体 API 契约一致）；</li>
 *   <li>登录 / SSO / Mock IdP 白名单不会被网关拦成未登录。</li>
 * </ol>
 *
 * <p>测试不依赖 Nacos（默认关闭）与真实后端：三条路由的 URI 都指向**不可达的固定端口**。
 * 这一条是踩过的：以前用默认的 127.0.0.1:8080/8081，本机刚好有服务在监听，
 * 测试结果就取决于那台机器上跑没跑东西（本机 8081 返回 401 时，白名单用例会假红）。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "GATEWAY_APP_URI=http://127.0.0.1:18080",
        "GATEWAY_IDENTITY_URI=http://127.0.0.1:18081",
        "GATEWAY_PLATFORM_URI=http://127.0.0.1:18082"
})
@AutoConfigureWebTestClient
class GatewayAuthTest {

    @Autowired
    private WebTestClient webTestClient;

    @Test
    void unauthenticatedApiRequestIsRejectedWith10002() {
        webTestClient.get().uri("/api/student/tickets")
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class)
                .value(body -> assertThat(body).contains("\"code\":10002"));
    }

    @Test
    void loginEndpointIsNotBlockedByGatewayAuth() {
        webTestClient.post().uri("/api/auth/login")
                .exchange()
                .expectStatus().value(status -> assertThat(status).isNotEqualTo(401));
    }

    @Test
    void nonApiPathIsNotGuarded() {
        webTestClient.get().uri("/actuator/health")
                .exchange()
                .expectStatus().value(status -> assertThat(status).isNotEqualTo(401));
    }
}
