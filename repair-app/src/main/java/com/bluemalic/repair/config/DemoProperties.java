package com.bluemalic.repair.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 演示部署的配置。**默认全关**，只有公网上的演示站才打开（写在服务器的 `.env` 里）。
 *
 * <ul>
 *   <li>{@code REPAIR_DEMO_ENABLED}：是否启用演示模式（默认 false）。true 才会装配定时重置任务</li>
 *   <li>{@code REPAIR_DEMO_TENANT_ID}：重置哪个租户的数据（默认 1，即 schema.sql 里的种子租户）</li>
 *   <li>{@code REPAIR_DEMO_PASSWORD}：演示账号的统一口令。**启用时必须给**，因为重置出来的账号得能登进去</li>
 *   <li>{@code REPAIR_DEMO_RESET_CRON}：重置时间（默认每天凌晨 4 点，此时没人看）</li>
 * </ul>
 *
 * <p><b>为什么要在配置上"必须给口令"这一道</b>：演示账号的口令是公开的（要写在登录页让访客看见），
 * 但它同时是一把能改数据的钥匙。如果允许留空，重置出的账号就没人能登录、演示站变成摆设，
 * 而失败现象是"登录页提示的账号登不上"——排查起来要绕一圈才知道是配置漏了。所以宁可启动就报错。
 *
 * <p>生产部署（`enabled=false`）下这个类只被构造、不做任何事，也不影响任何现有行为。
 */
@Getter
@Component
public class DemoProperties {

    private final boolean enabled;

    private final long tenantId;

    private final String password;

    private final String resetCron;

    public DemoProperties(@Value("${repair.demo.enabled:false}") boolean enabled,
                          @Value("${repair.demo.tenant-id:1}") long tenantId,
                          @Value("${repair.demo.password:}") String password,
                          @Value("${repair.demo.reset-cron:0 0 4 * * *}") String resetCron) {
        if (enabled && (password == null || password.isBlank())) {
            throw new IllegalStateException(
                    "开启了演示模式（repair.demo.enabled=true）但没设演示口令。"
                            + "请在 .env 里设置 REPAIR_DEMO_PASSWORD —— 演示账号的口令要写在登录页给访客用，"
                            + "留空的话重置出来的账号谁也登不进去。");
        }
        this.enabled = enabled;
        this.tenantId = tenantId;
        this.password = password;
        this.resetCron = resetCron;
    }
}
