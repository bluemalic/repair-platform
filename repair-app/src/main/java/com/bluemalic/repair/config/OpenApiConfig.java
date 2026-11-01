package com.bluemalic.repair.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * OpenAPI 的鉴权声明（CLAUDE/AGENTS 里记的「工程尾巴」之一）。
 *
 * <p>没有它，Knife4j 页面没有 Authorize 按钮，联调时只能手工把 token 贴到每一个请求上。
 * 项目用的是 Sa-Token 的**不透明 token**（不是 JWT），但请求头同样是
 * {@code Authorization: Bearer <tokenValue>}，所以这里按 HTTP bearer 声明。
 *
 * <p>全局声明意味着登录接口在文档里也会显示锁图标；这是文档展示问题，不影响真实鉴权
 * （服务端白名单由 Sa-Token 配置决定）。
 */
@Configuration
public class OpenApiConfig {

    private static final String SCHEME_NAME = "sa-token";

    @Bean
    public OpenAPI repairOpenAPI() {
        return new OpenAPI()
                .info(new Info()
                        .title("高校后勤报修平台 API")
                        .description("租户域 /api/student|worker|admin/** 与平台域 /api/platform/**")
                        .version("v1"))
                .components(new Components().addSecuritySchemes(SCHEME_NAME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("Sa-Token")
                                .description("登录接口返回的 tokenValue")))
                .addSecurityItem(new SecurityRequirement().addList(SCHEME_NAME));
    }
}
