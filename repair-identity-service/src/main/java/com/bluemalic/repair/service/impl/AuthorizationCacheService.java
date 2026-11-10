package com.bluemalic.repair.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bluemalic.repair.config.CacheConfig;
import com.bluemalic.repair.entity.SysPermission;
import com.bluemalic.repair.entity.SysRole;
import com.bluemalic.repair.entity.SysRolePermission;
import com.bluemalic.repair.entity.SysUserRole;
import com.bluemalic.repair.mapper.SysPermissionMapper;
import com.bluemalic.repair.mapper.SysRoleMapper;
import com.bluemalic.repair.mapper.SysRolePermissionMapper;
import com.bluemalic.repair.mapper.SysUserRoleMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 鉴权结果的缓存层（阶段 5 · ADR-020）。
 *
 * <p><b>为什么单独一个 Bean</b>：{@code @Cacheable} 靠 Spring 代理生效，**同一个类内部的自调用不会走代理**。
 * 如果把缓存方法放在 {@code StpInterfaceImpl} 里再自己调自己，缓存会静默失效——代码看着有缓存、
 * 实际每次都回库，而且没有任何报错。拆出来是让代理链成立，不是过度设计。
 *
 * <p><b>缓存的是最终结果（用户 → 权限码 / 角色码），不是中间的角色 id</b>：值是纯字符串列表，
 * JSON 反序列化不会出现"数字变成 Integer 再当 Long 用"的类型坑。
 *
 * <p><b>失效策略</b>：角色与权限在本项目里没有运行期管理接口（只由 {@code R__seed.sql} 在启动时维护），
 * 所以 TTL 之外只在**应用启动后清一次**（见 {@code AuthorizationCacheBootstrap}）——
 * 否则 Redis 里上一版进程留下的权限缓存会让新种子数据最多 30 分钟不生效。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthorizationCacheService {

    private final SysUserRoleMapper sysUserRoleMapper;
    private final SysRoleMapper sysRoleMapper;
    private final SysRolePermissionMapper sysRolePermissionMapper;
    private final SysPermissionMapper sysPermissionMapper;
    private final CacheManager cacheManager;

    /** 用户的权限码（启用中的）。命中缓存时**完全不回库**。 */
    @Cacheable(cacheNames = CacheConfig.CACHE_USER_PERMS, key = "#userId")
    public List<String> permissionCodes(long userId) {
        List<Long> roleIds = roleIdsOf(userId);
        if (roleIds.isEmpty()) {
            return List.of();
        }
        List<Long> permissionIds = sysRolePermissionMapper
                .selectList(Wrappers.<SysRolePermission>lambdaQuery()
                        .in(SysRolePermission::getRoleId, roleIds))
                .stream()
                .map(SysRolePermission::getPermissionId)
                .distinct()
                .toList();
        if (permissionIds.isEmpty()) {
            return List.of();
        }
        return sysPermissionMapper.selectByIds(permissionIds).stream()
                .filter(permission -> Integer.valueOf(1).equals(permission.getStatus()))
                .map(SysPermission::getCode)
                .distinct()
                .toList();
    }

    /** 用户的角色码（启用中的）。 */
    @Cacheable(cacheNames = CacheConfig.CACHE_USER_ROLES, key = "#userId")
    public List<String> roleCodes(long userId) {
        List<Long> roleIds = roleIdsOf(userId);
        if (roleIds.isEmpty()) {
            return List.of();
        }
        return sysRoleMapper.selectByIds(roleIds).stream()
                .filter(role -> Integer.valueOf(1).equals(role.getStatus()))
                .map(SysRole::getCode)
                .distinct()
                .toList();
    }

    /**
     * 清空鉴权缓存。**由启动引导调用**（见 {@code AuthorizationCacheBootstrap}）：
     * Redis 会跨进程存活，上一版进程写下的权限缓存必须作废，否则种子里的权限调整要等 TTL 才生效。
     */
    public void evictAll() {
        clear(CacheConfig.CACHE_USER_PERMS);
        clear(CacheConfig.CACHE_USER_ROLES);
    }

    private void clear(String cacheName) {
        Cache cache = cacheManager.getCache(cacheName);
        if (cache != null) {
            cache.clear();
            log.info("已清空鉴权缓存 cache={}", cacheName);
        }
    }

    private List<Long> roleIdsOf(long userId) {
        return sysUserRoleMapper
                .selectList(Wrappers.<SysUserRole>lambdaQuery().eq(SysUserRole::getUserId, userId))
                .stream()
                .map(SysUserRole::getRoleId)
                .distinct()
                .toList();
    }
}
