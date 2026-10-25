package com.bluemalic.repair;

import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 集成测试的统一入口注解。**所有测试类都必须用它，不要各自写 @SpringBootTest 组合。**
 *
 * <p><b>为什么必须统一</b>：Spring 的测试上下文按"注解组合"缓存，组合不同就会在同一个 JVM 里
 * 并存多个 ApplicationContext（各带一份 DataSource / 连接池 / 事务管理器）。而 Sa-Token 的
 * {@code StpInterface}（本项目是 {@code StpInterfaceImpl}）是**按上下文启动顺序写进 SaManager
 * 全局静态字段**的——多上下文时，后启动的会覆盖先启动的。于是 A 上下文的请求可能去调用
 * B 上下文的 {@code StpInterfaceImpl}，用它绑定的 DataSource 连接查库，**读不到 A 上下文测试
 * 事务里未提交的数据**，表现成"注入的测试用户查不到角色，鉴权报 10003"。
 *
 * <p>这个坑在不同操作系统上触发时机不同（类扫描顺序不同），会出现"本地全绿、CI 必红"的假象。
 * 统一注解后全测试只起一个上下文，问题从根上消失——也顺带省掉每个类重复启动 Spring 的开销。
 *
 * <p>{@code @Transactional} 让每个测试方法结束后自动回滚；配合 MockMvc 跑在**同一个线程**，
 * 测试事务里插入的数据对请求可见（这也是不能用 RANDOM_PORT 的原因）。
 *
 * <p>{@code properties} 关掉超时调度器：后台 @Scheduled 线程看不到测试未提交的数据，
 * 只会在库里/Redis 上空转刷日志（超时逻辑本身由 TimeoutAutoCloseTest 直接调服务方法验证）。
 * 这里属性写死在这个组合注解里，所有测试类配置一致 → 仍然共用一个 Spring 上下文。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@SpringBootTest(properties = {
        "repair.timeout.scheduler-enabled=false",
        // Outbox relay 的定时触发在测试里关掉：它会在每个库上空转、并和测试事务抢数据。
        // 需要验证 relay 的用例直接调用 OutboxRelayJob#relayOnce（包级可见）。
        "repair.outbox.relay-enabled=false",
        // 测试不依赖 MQ：RocketMQ 关闭时 Outbox 走本地日志消费者，集成测试不需要 broker。
        "repair.rocketmq.enabled=false",
        // 测试保持同步站内信，现有断言（请求返回后立刻查通知）不用改。
        "repair.notification.delivery=sync",
        // 演示模式的开关**保持默认的 false**：这样测试里 DemoResetJob 根本不会被装配，
        // 后台线程也就不会跑去重置数据。这里只给一个演示口令，供 DemoResetTest 验证
        // "重置后演示账号能登录"——口令为空时登录接口过不了 @NotBlank 校验。
        "repair.demo.password=Demo@123456",
        // AI 的只读账号：给测试一个固定账号名，**账号本身由用例按需创建**（CREATE USER 是 DDL，
        // 不参与事务回滚，所以不能指望启动引导在测试里替我们建——这里只保证"配置是齐的"，
        // 让问数链路能走到真正连库那一步。模型那一侧由测试作用域的 TestAiAssistant 顶替，
        // 所以 CI 既不调模型也不花钱。
        "AI_DB_USERNAME=test_ai_ci",
        "AI_DB_PASSWORD=Test@123456",
        // 统一认证：限流放宽到 1000（一个测试类里会连着发起十几次授权/回调，默认 30/分钟会把自己挡住），
        // 并打开演示用的 Mock IdP——它的端点要能在共享上下文里被测试直接驱动
        "repair.sso.max-attempts-per-minute=1000",
        "repair.sso.mock-idp-enabled=true"
})
@AutoConfigureMockMvc
// Spring Boot 3 在 @SpringBootTest 里**默认关闭可观测性导出**（ObservabilityContextCustomizer），
// 不打开的话 Prometheus 注册表与 /actuator/prometheus 在测试里根本不存在——
// 而生产（java -jar）是开着的，测试就测不到真实形态（阶段 5 踩过）。
// 放在这个统一注解上而不是某一个测试类上：换注解组合会多起一个 Spring 上下文，
// 而 Sa-Token 的静态字段在多上下文下会串（见类注释）。
@AutoConfigureObservability
@Transactional
public @interface IntegrationTest {
}
