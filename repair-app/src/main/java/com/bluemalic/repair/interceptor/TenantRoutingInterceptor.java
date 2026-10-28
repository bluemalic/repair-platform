package com.bluemalic.repair.interceptor;

import com.bluemalic.repair.common.BizException;
import com.bluemalic.repair.common.ErrorCode;
import com.bluemalic.repair.service.CurrentTenantService;
import com.bluemalic.repair.tenancy.TenantDataMigrator;
import com.bluemalic.repair.tenancy.TenantRoutingContext;
import com.bluemalic.repair.tenancy.TenantSchemaCatalog;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 把"这次请求属于哪个学校"钉到线程上，决定后续所有 SQL 连哪个库（阶段 6 · ADR-021）。
 *
 * <p><b>为什么它必须排在所有拦截器之前（order -1）</b>：鉴权本身就要查库——
 * `@SaCheckPermission` → `StpInterface` → 角色/权限表。那些表在**租户自己的库**里，
 * 所以"先在 order 0 校验登录、再去查库"是来不及的：查库那一步必须先知道去哪个库。
 * 顺序成了依赖，所以这里的 order 是负数，且四个既有的注释（`SaTokenConfigure` /
 * `RateLimitConfig` / `MustChangePasswordConfig` / `PlatformScopeConfig`）都跟着更新了。
 *
 * <p><b>为什么不用 Filter</b>：Filter 里抛的异常到不了 `@RestControllerAdvice`，
 * 租户解析失败（登录态里的租户在平台库找不到）就会变成裸 500 而不是一个能看懂的业务错误。
 * 拦截器里抛则照常被全局异常处理器兜住。
 *
 * <p><b>为什么必须清（而且是两处清）</b>：Tomcat 的工作线程是复用的。
 * 一个请求留下的路由键会作用到下一个借到这条线程的请求上——那是"读到别家数据"的量级，
 * 所以正常路径在 {@code afterCompletion} 清，preHandle 抛异常时（此时 Spring 不会再调
 * afterCompletion）在 catch 里清。**没有第三种情况**：键只在 preHandle 里设置。
 *
 * <p><b>租户解析不出来时为什么不放行</b>：`TenantSchemaCatalog` 会抛错，这里让它抛。
 * 放行（键为空 = 平台库）等于把某所学校的数据操作送到平台库上去执行——报错会有人来看，
 * 静默走错库不会。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class TenantRoutingInterceptor implements HandlerInterceptor {

    private final CurrentTenantService currentTenantService;

    private final TenantSchemaCatalog catalog;

    /** 正在切换数据库的学校：那几秒里它的请求一律拒绝，避免有写入落在正在被替换的源库上 */
    private final TenantDataMigrator dataMigrator;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod)) {
            // 静态资源、404 之类没有 handler method 的请求不碰库，也就无所谓路由
            return true;
        }
        try {
            Long tenantId = currentTenantService.tenantIdOrNull();
            if (tenantId != null) {
                if (dataMigrator.isSwitching(tenantId)) {
                    // 切换那几秒：宁可让这次请求失败（并说清原因），也不能让它写进正在被换掉的源库
                    throw new BizException(ErrorCode.SYSTEM_ERROR, "这所学校正在切换数据库，请稍后重试（约几秒）");
                }
                TenantRoutingContext.set(catalog.routingKeyFor(tenantId));
            }
            // tenantId 为 null = 没有登录态（登录接口本身、健康检查）→ 键留空 = 平台库，
            // 这正是登录接口需要的：它要按学校编码在平台库里找到那所学校
            return true;
        } catch (RuntimeException e) {
            TenantRoutingContext.clear();
            throw e;
        }
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        TenantRoutingContext.clear();
    }
}
