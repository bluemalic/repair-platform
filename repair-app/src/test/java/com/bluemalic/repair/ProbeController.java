package com.bluemalic.repair;

import cn.dev33.satoken.annotation.SaCheckPermission;
import com.bluemalic.repair.common.Result;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 测试专用的鉴权探针接口，用来钉住 {@code @SaCheckPermission} 真的生效。
 *
 * <p>刻意放在**顶层**而不是嵌套在某个测试类里：它在本包（{@code @SpringBootApplication}
 * 的扫描范围内）会被组件扫描自动注册，因此不需要在测试类上写 {@code @Import}——
 * 一旦写了 {@code @Import}，那个测试类的注解组合就和别的类不同，会多起一个 Spring 上下文，
 * 触发 {@link IntegrationTest} 里说明的多上下文问题。
 */
@RestController
public class ProbeController {

    @SaCheckPermission("ticket:dispatch")
    @GetMapping("/api/admin/probe/dispatch")
    public Result<String> dispatch() {
        return Result.ok("ok");
    }
}
