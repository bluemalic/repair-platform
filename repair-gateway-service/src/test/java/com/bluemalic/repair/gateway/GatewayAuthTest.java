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
 * <p>测试不依赖 Nacos（默认关闭）与真实后端：路由指向一个不可达地址，
 * 白名单用例只断言"没有被网关判成 401"，不关心下游连接失败。
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
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
