package com.bluemalic.repair.notification;

import com.bluemalic.repair.config.RocketMqConsumerConfig;
import com.bluemalic.repair.config.RocketMqProperties;
import com.bluemalic.repair.config.TenancyProperties;
import com.bluemalic.repair.config.TenantDataSourceConfig;
import com.bluemalic.repair.mq.RocketMqEventConsumer;
import com.bluemalic.repair.service.impl.NotificationConsumerService;
import com.bluemalic.repair.service.impl.NotificationServiceImpl;
import com.bluemalic.repair.tenancy.TenantDataSourceRegistry;
import com.bluemalic.repair.tenancy.TenantSchemaCatalog;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.annotation.Import;

/**
 * notification-service 的独立进程入口（阶段 2 · ADR-023）。
 *
 * <p>当前与单体**共用同一个 jar**，只导入消费者链路需要的 Bean：
 * 租户数据源 / 目录、通知服务、消费幂等、RocketMQ consumer。
 * 没有 Web 容器、没有 Sa-Token、没有工单服务，因此它只消费事件、写 notification。
 *
 * <p>启动时关掉 Flyway（表结构由单体/部署负责）：见 {@code application-notification.yml}。
 * 后续如果要把 artifact 也拆开，只需要把这个包和它 import 的类搬到独立模块，不需要改业务代码。
 */
@SpringBootApplication
@MapperScan("com.bluemalic.repair.mapper")
@Import({
        TenantDataSourceConfig.class,
        TenancyProperties.class,
        TenantDataSourceRegistry.class,
        TenantSchemaCatalog.class,
        NotificationServiceImpl.class,
        NotificationConsumerService.class,
        RocketMqProperties.class,
        RocketMqConsumerConfig.class,
        RocketMqEventConsumer.class
})
public class NotificationServiceApplication {

    public static void main(String[] args) {
        new SpringApplicationBuilder(NotificationServiceApplication.class)
                .web(WebApplicationType.NONE)
                .run(args);
    }
}
