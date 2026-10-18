package com.bluemalic.repair.tenancy;

import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.function.Supplier;

/**
 * 本次请求（或本次任务）该用哪个库——**线程上的路由键**（阶段 6 · ADR-021）。
 *
 * <p>它回答一个问题：`ticketMapper.selectList(...)` 到底连哪个 database。取值的来源只有两处：
 * <ul>
 *   <li>{@code TenantRoutingInterceptor} 在请求进 Controller 之前按登录态设置（正常路径）；</li>
 *   <li>{@code TenantSchemaCatalog} 在**平台运营**操作某个具体学校时显式设置（例如"给 5 号学校加一个管理员"）。</li>
 * </ul>
 *
 * <p><b>键为空 = 平台库</b>：无登录态的请求（登录接口、健康检查、文档）与定时任务是"系统上下文"，
 * 它们本来就不属于任何学校。这与"未知租户"是**两件事**，别混：租户解析不出来时
 * {@code TenantSchemaCatalog} 直接抛错（fail-closed），绝不会悄悄回落到平台库。
 *
 * <p><b>为什么不用 `USE 库名` 切换、也不用"共享连接池 + 切库"</b>：MySQL 没有恢复会话默认库的
 * 机制，连接还回池里时仍指向上一个库，下一个借到它的人就读到别人的数据，而且**不会报错**
 * （spike 实测见 ADR-021）。所以每个租户一个独立连接池，路由键选的是"池"，不是"库"。
 *
 * <p><b>为什么 {@link #call} 不许在事务中换库</b>：`@Transactional` 会把一条连接绑在**数据源对象**上，
 * 事务中途换路由键不会换连接（连接已经借出去了），于是"在事务里切租户"会变成**静默写错库**。
 * 这里直接抛错：这种代码结构本身就是错的，应该在事务边界之外切。
 */
public final class TenantRoutingContext {

    /**
     * 平台库（`repair`）的路由键：控制面（`tenant` 等）与**过渡期里存量租户的数据**都在这个库里。
     *
     * <p>取值带尖括号是有意的：库名不可能含尖括号，所以它与任何租户库名都不会撞。
     */
    public static final String CONTROL = "<control>";

    private static final ThreadLocal<String> KEY = new ThreadLocal<>();

    private TenantRoutingContext() {
    }

    /** 当前路由键；{@code null} 表示"系统上下文/未定"，由数据源按平台库处理。 */
    public static String currentKey() {
        return KEY.get();
    }

    public static void set(String key) {
        KEY.set(key);
    }

    /**
     * 清掉路由键。**必须与 {@link #set} 成对**：Tomcat 的工作线程是复用的，
     * 残留的键会让下一个请求读到上一个租户的库。
     */
    public static void clear() {
        KEY.remove();
    }

    /**
     * 在指定路由键下执行（可嵌套）：进入前保存旧值，退出后原样恢复。
     *
     * <p>嵌套是必需的：平台运营"给某学校建账号"这一类操作，外层是平台库（读 `tenant` 表），
     * 内层要切到那个学校的库写账号，然后回到平台库继续。
     */
    public static <T> T call(String key, Supplier<T> action) {
        String previous = currentKey();
        requireSwitchable(previous, key);
        set(key);
        try {
            return action.get();
        } finally {
            if (previous == null) {
                clear();
            } else {
                set(previous);
            }
        }
    }

    /** {@link #call} 的 Runnable 版本。 */
    public static void run(String key, Runnable action) {
        call(key, () -> {
            action.run();
            return null;
        });
    }

    /** 在**平台库**里执行（控制面读写的唯一正确姿势：`tenant` 表只在平台库）。 */
    public static <T> T callControl(Supplier<T> action) {
        return call(CONTROL, action);
    }

    private static void requireSwitchable(String previous, String key) {
        if (previous == null || previous.equals(key)) {
            return;
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("不能在事务中切换租户路由：事务已绑定 " + previous
                    + " 的连接，切到 " + key + " 不会换连接，只会静默写错库。请在事务边界之外切换");
        }
    }
}
