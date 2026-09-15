package com.bluemalic.repair;

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
@SpringBootTest(properties = "repair.timeout.scheduler-enabled=false")
@AutoConfigureMockMvc
@Transactional
public @interface IntegrationTest {
}
