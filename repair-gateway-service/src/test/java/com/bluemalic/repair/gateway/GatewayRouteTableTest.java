package com.bluemalic.repair.gateway;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 路由表的形状约束（阶段 3.2 · docs/03 §4.1）。
 *
 * <p>为什么值得单独测：路由表是 YAML，写错（拼错前缀、漏了 order、被通配吃掉）**不会编译报错**，
 * 只会在某个接口上表现为 404 或落到错误的服务。这里把三条最容易犯的错钉住：
 * <ol>
 *   <li>拆出去的四段前缀必须指向 identity / platform，而不是还留在 app；</li>
 *   <li>具体路径的 order 必须小于它的通配父路径（advice / worker 范围）；</li>
 *   <li>不许出现 {@code /api/**} 这种默认兜底——漏配应当可见地 404。</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "GATEWAY_APP_URI=http://127.0.0.1:18080",
        "GATEWAY_IDENTITY_URI=http://127.0.0.1:18081",
        "GATEWAY_PLATFORM_URI=http://127.0.0.1:18082",
        "GATEWAY_TICKET_URI=http://127.0.0.1:18083",
        "GATEWAY_AI_URI=http://127.0.0.1:18084"
})
class GatewayRouteTableTest {

    @Autowired
    private RouteDefinitionLocator routeDefinitionLocator;

    @Test
    void 身份与平台前缀指向各自服务() {
        Map<String, RouteDefinition> routes = routes();

        assertThat(routes).containsKeys("identity-auth", "identity-students", "identity-workers",
                "identity-audit-logs", "platform-tenants");
        assertThat(routes.get("identity-auth").getUri().toString()).contains("18081");
        assertThat(routes.get("platform-tenants").getUri().toString()).contains("18082");
    }

    @Test
    void AI前缀指向ai服务() {
        Map<String, RouteDefinition> routes = routes();

        assertThat(routes).containsKeys("ticket-ai", "ticket-student-agent", "ticket-worker-advice",
                "ticket-admin-knowledge", "ai-ticket-triage");
        for (String id : List.of("ticket-ai", "ticket-student-agent", "ticket-worker-advice",
                "ticket-admin-knowledge", "ai-ticket-triage")) {
            assertThat(routes.get(id).getUri().toString())
                    .as("%s 属于 AI 域，应当指向 GATEWAY_AI_URI", id)
                    .contains("18084");
        }
    }

    @Test
    void 工单前缀指向ticket服务而统计与通知仍留在app() {
        Map<String, RouteDefinition> routes = routes();

        for (String id : List.of("ticket-admin-tickets", "ticket-worker-tickets", "ticket-by-code",
                "ticket-dictionaries", "ticket-admin-base-data")) {
            assertThat(routes.get(id).getUri().toString())
                    .as("%s 属于工单域", id).contains("18083");
        }
        // 路由 id 前缀是 ticket-，但实现在 ai / 统计 / 通知链路里，仍指 app
        for (String id : List.of("ticket-admin-statistics", "ticket-notifications", "ticket-files")) {
            assertThat(routes.get(id).getUri().toString())
                    .as("%s 不属于工单域，应留在 app", id).contains("18080");
        }
    }

    @Test
    void 具体路径排在通配路径之前() {
        Map<String, RouteDefinition> routes = routes();

        assertThat(routes.get("ticket-worker-advice").getOrder())
                .as("/api/worker/tickets/*/advice 必须先于 /api/worker/tickets/**")
                .isLessThan(routes.get("ticket-worker-tickets").getOrder());
        assertThat(routes.get("ai-ticket-triage").getOrder())
                .as("/api/admin/tickets/*/triage 必须先于 /api/admin/tickets/**")
                .isLessThan(routes.get("ticket-admin-tickets").getOrder());
        assertThat(routes.get("ticket-worker-scope-buildings").getOrder())
                .as("PUT /api/admin/workers/*/buildings 必须先于 /api/admin/workers/**")
                .isLessThan(routes.get("identity-workers").getOrder());
        assertThat(routes.get("ticket-worker-scope-skills").getOrder())
                .as("PUT /api/admin/workers/*/skills 必须先于 /api/admin/workers/**")
                .isLessThan(routes.get("identity-workers").getOrder());
    }

    @Test
    void 没有默认兜底路由() {
        // 有 /api/** 就意味着"漏配的路由会静默落到 app"，与 §4.1 规则 5 冲突
        assertThat(routes().values())
                .noneMatch(route -> route.getPredicates().stream()
                        .anyMatch(predicate -> "/api/**".equals(predicate.getArgs().get("pattern"))
                                || "/api/**".equals(predicate.getArgs().get("_genkey_0"))));
    }

    private Map<String, RouteDefinition> routes() {
        List<RouteDefinition> definitions = routeDefinitionLocator.getRouteDefinitions()
                .collectList().block();
        assertThat(definitions).isNotNull();
        return definitions.stream().collect(Collectors.toMap(RouteDefinition::getId, Function.identity()));
    }
}
