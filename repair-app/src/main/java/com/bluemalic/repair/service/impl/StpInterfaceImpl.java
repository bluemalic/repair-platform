package com.bluemalic.repair.service.impl;

import cn.dev33.satoken.stp.StpInterface;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 权限码 / 角色码的提供者。
 *
 * <p><b>这是注解鉴权的必要前提。</b> {@code @SaCheckPermission("ticket:dispatch")} 只声明了
 * "需要什么权限"，Sa-Token 自己并不知道"谁有什么权限"——它会回调本类来问。
 * 少这个实现，所有注解校验都会失败。Sa-Token 会按类型从 Spring 容器里找它，所以加
 * {@code @Service} 就够了，不需要额外注册。
 *
 * <p>查询链路是数据权限那一套的"鉴权版"：{@code sys_user_role} → {@code sys_role_permission}
 * → {@code sys_permission}。注意它和 MyBatis 拦截器做的**数据权限**是两件事：
 * 这里管"能不能调这个接口"，拦截器管"能看哪些数据"。
 *
 * <p>2026-10-17（阶段 5 · ADR-020）：权限列表已加 Redis 缓存，见 {@link AuthorizationCacheService}。
 * 本类退化为**薄委托**——缓存必须放在另一个 Bean 里才走得到 Spring 的代理，
 * 同类自调用不会触发 {@code @Cacheable}（这条坑写在那个类的注释里）。
 */

@Service
@RequiredArgsConstructor
public class StpInterfaceImpl implements StpInterface {

    private final AuthorizationCacheService authorizationCacheService;

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        return authorizationCacheService.permissionCodes(userIdOf(loginId));
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        return authorizationCacheService.roleCodes(userIdOf(loginId));
    }

    private long userIdOf(Object loginId) {
        return Long.parseLong(String.valueOf(loginId));
    }
}
