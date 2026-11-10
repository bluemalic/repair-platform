package com.bluemalic.repair;

import com.bluemalic.repair.tenancy.TenantRoutingContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.test.context.TestContext;
import org.springframework.test.context.TestExecutionListener;

/**
 * 把 {@link TenantTestDb} 声明的租户库写进路由键（阶段 2.3）。
 *
 * <p><b>为什么必须在事务之前设置</b>：{@code @Transactional} 会把连接绑在数据源上，
 * 事务已经开启后再换路由键不会换连接。这个监听器的 order 是 {@link Ordered#HIGHEST_PRECEDENCE}，
 * 排在 Spring 的事务监听器之前，所以事务拿连接时读到的已经是对应租户库的池。
 *
 * <p>没有标注 {@link TenantTestDb} 的测试类**不设置路由键**——保持"系统上下文 = 平台库"的默认行为。
 */
public class TenantRoutingTestExecutionListener implements TestExecutionListener, Ordered {

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void beforeTestMethod(TestContext testContext) {
        TenantTestDb annotation = AnnotatedElementUtils.findMergedAnnotation(
                testContext.getTestMethod(), TenantTestDb.class);
        if (annotation == null) {
            annotation = AnnotatedElementUtils.findMergedAnnotation(
                    testContext.getTestClass(), TenantTestDb.class);
        }
        if (annotation != null) {
            TenantRoutingContext.set(annotation.value());
        }
    }

    @Override
    public void afterTestMethod(TestContext testContext) {
        // 工作线程是复用的：不清掉会让下一个用例读到这个用例的库
        TenantRoutingContext.clear();
    }
}
