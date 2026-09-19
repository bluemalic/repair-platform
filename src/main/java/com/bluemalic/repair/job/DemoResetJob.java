package com.bluemalic.repair.job;

import com.bluemalic.repair.service.DemoResetService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 演示数据每天重置一次。**默认不装配**（{@code repair.demo.enabled} 不为 true 时这个 Bean 根本不存在），
 * 所以生产部署里它连定时器都不会注册。
 *
 * <p>与 {@code TimeoutScheduler} 的日志策略不同：那边每秒跑一轮、必须压制重复告警；
 * 这里一天只跑一次，失败就该把完整堆栈打出来——真要一天只失败一次还不留线索，那就查不动了。
 *
 * <p>时间放在凌晨 4 点（可配）：重置过程会短暂删掉全部工单，落在有人看站点的时段会很突兀。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "repair.demo", name = "enabled", havingValue = "true")
public class DemoResetJob {

    private final DemoResetService demoResetService;

    @Scheduled(cron = "${repair.demo.reset-cron:0 0 4 * * *}")
    public void reset() {
        try {
            demoResetService.reset();
        } catch (Exception e) {
            // 不向上抛：抛出去会被 Spring 的调度器再打一遍堆栈。下次到点会自然重试。
            log.error("演示数据重置失败（下个周期会重试）", e);
        }
    }

    /**
     * 应用启动完成后也重置一次。
     *
     * <p>两个用途：① 刚开启演示模式时不用等到凌晨才有演示数据；② 演示站的"重启即恢复"——
     * 数据被玩坏了，`docker compose restart app` 就回到初始状态，比等定时任务及时得多。
     *
     * <p>这个行为**只存在于演示模式**：`enabled` 不为 true 时整个 Bean 都不会装配，
     * 所以生产部署里重启应用不会碰任何数据。
     */
    @EventListener(ApplicationReadyEvent.class)
    public void resetOnStartup() {
        reset();
    }
}
