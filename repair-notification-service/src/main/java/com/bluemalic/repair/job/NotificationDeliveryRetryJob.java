package com.bluemalic.repair.job;

import com.bluemalic.repair.service.impl.NotificationDeliveryServiceImpl;
import com.bluemalic.repair.tenancy.TenantRoutingContext;
import com.bluemalic.repair.tenancy.TenantSchemaCatalog;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 通知投递重试（阶段 4 · ADR-030 决定 5）。
 *
 * <p>为什么必须逐库跑：重试是**系统任务**，没有登录态，数据权限拦截器不注入任何条件，
 * 一次调用只能看到当前路由到的那个库（AGENTS §5.8）。所以平台库 + 每个已开通学校各跑一遍。
 *
 * <p>为什么要限流（每次最多 100 条）：外部接口慢的时候，一轮重试可能跑很久；
 * 下一轮由 {@code fixedDelay} 在上一轮结束后才开始，不会堆叠。真积压了看 DEAD 的数量与日志。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class NotificationDeliveryRetryJob {

    private static final int BATCH = 100;

    private final NotificationDeliveryServiceImpl deliveryService;

    private final TenantSchemaCatalog schemaCatalog;

    @Scheduled(fixedDelayString = "${repair.notification.delivery-retry-ms:60000}",
            initialDelayString = "${repair.notification.delivery-retry-initial-ms:30000}")
    public void retryDueDeliveries() {
        Set<String> keys = new LinkedHashSet<>(schemaCatalog.provisionedSchemas().values());
        keys.add(TenantRoutingContext.CONTROL);
        int total = 0;
        for (String key : keys) {
            try {
                Integer done = TenantRoutingContext.call(key, () -> deliveryService.retryDue(BATCH));
                total += done == null ? 0 : done;
            } catch (Exception e) {
                // 一个库坏了不影响其它学校（与 TenantSchemaBootstrap 同一个取舍）
                log.error("通知投递重试失败 schema={}（其它库不受影响）", key, e);
            }
        }
        if (total > 0) {
            log.info("通知投递重试完成，本轮尝试 {} 条", total);
        }
    }
}
