package com.bluemalic.repair.tenancy;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 启动时把**每个租户库**升级到最新结构（阶段 6 · ADR-021）。
 *
 * <p>为什么必须有这一步：租户库的结构是"开通那天跑过的脚本"那个版本，之后平台加了列、加了权限码，
 * 学校不会自动跟上——而版本迁移与可重复迁移（平台角色/权限种子）都需要重跑一次才能生效。
 * 启动时逐个库跑一遍 Flyway 是最省事也最不容易忘的时机。
 *
 * <p><b>一个学校失败不影响其它学校，也不阻断启动</b>（照 {@code DemoResetJob} /
 * {@code AiReadOnlyAccountBootstrap} 的取舍）：隔离的意义就是"某所学校自己的库坏了，别的学校照常用"。
 * 那所学校自己的请求会**响亮地失败**（表不存在 / 库连不上）——报错，而不是静默换库。
 * 反过来，若在这里阻断启动，一所学校的问题会变成所有人的问题。
 *
 * <p>与 AI 只读账号引导那一步有重叠：那一步只管平台库（没有租户库时也得成立），
 * 这一步负责把每个租户库都包含进去。两次都是幂等的。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TenantSchemaBootstrap {

    private final TenantSchemaCatalog catalog;

    private final TenantDatabaseProvisioner provisioner;

    @EventListener(ApplicationReadyEvent.class)
    public void syncTenantSchemas() {
        Map<Long, String> schemas = catalog.provisionedSchemas();
        if (schemas.isEmpty()) {
            // 还没有学校搬到独立库（全新部署、或过渡期刚开始）：没有任何事要做
            return;
        }
        int migrated = 0;
        for (Map.Entry<Long, String> entry : schemas.entrySet()) {
            try {
                provisioner.migrate(entry.getValue());
                migrated++;
            } catch (Exception e) {
                log.error("租户库结构同步失败 tenantId={} schema={}（这个学校的请求会报错，其它学校不受影响）",
                        entry.getKey(), entry.getValue(), e);
            }
        }
        log.info("租户库结构同步完成：{}/{} 个库已是最新", migrated, schemas.size());
        // 迁移之后才刷授权：新加的权限码不影响授权，但新加的表会影响（白名单里的表被 GRANT）
        provisioner.ensureAiReadOnlyOnAllDatabases();
    }
}
