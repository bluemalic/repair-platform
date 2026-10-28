package com.bluemalic.repair.job;

import com.bluemalic.repair.config.PlatformProperties;
import com.bluemalic.repair.entity.SysUser;
import com.bluemalic.repair.service.AccountService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 平台运营账号的引导创建。应用启动完成后跑一次，**没配就不做事**（配置见
 * {@link PlatformProperties}）。
 *
 * <p>它是平台账号的**唯一**创建入口：没有任何接口能造出平台账号
 * （{@code TenantProvisionService} 里所有建号都写死"后勤管理员"，照
 * {@code StudentCreateDTO} 那条"服务端写死类型"的约定）。
 *
 * <p>为什么放在启动时机而不是首次请求时懒创建：引导出来的账号带"首次登录必须改密"，
 * 早建早改；而懒创建会在"第一次真的要用它"的时候才失败，那时人已经在等着开学校了。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PlatformBootstrapJob {

    private final AccountService accountService;
    private final PlatformProperties platformProperties;

    @EventListener(ApplicationReadyEvent.class)
    public void bootstrapOnStartup() {
        if (!platformProperties.bootstrapConfigured()) {
            return;
        }
        String username = platformProperties.getBootstrapUsername();
        try {
            SysUser created = accountService.ensurePlatformAccount(username,
                    platformProperties.getBootstrapPassword(),
                    platformProperties.getBootstrapRealName());
            if (created == null) {
                // 说清"为什么没生效"和"怎么才能生效"，否则最容易的误操作是反复改环境变量重启
                log.info("平台运营账号 {} 已存在，跳过引导（引导只建不改；"
                        + "忘记口令时删掉该账号再重启，见 docs/05 §4.3）", username);
            } else {
                log.info("已引导创建平台运营账号 {}（首次登录必须改密）", username);
            }
        } catch (Exception e) {
            // 不向上抛：引导失败是"少一个运营入口"，不该让应用起不来（照 DemoResetJob 的取舍）
            log.error("平台运营账号引导失败（不影响应用启动，修好配置后重启即可）", e);
        }
    }
}
