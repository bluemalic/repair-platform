package com.bluemalic.repair.service.impl;

import cn.dev33.satoken.stp.StpInterface;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
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
 * <p>代价说明：每次权限校验都会查库（Sa-Token 默认不缓存权限列表）。当前量级够用；
 * 若将来校验频率显著上升，在这里加缓存即可，不必改调用方。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class StpInterfaceImpl implements StpInterface {

    private final SysUserRoleMapper sysUserRoleMapper;
    private final SysRoleMapper sysRoleMapper;
    private final SysRolePermissionMapper sysRolePermissionMapper;
    private final SysPermissionMapper sysPermissionMapper;

    @Override
    public List<String> getPermissionList(Object loginId, String loginType) {
        List<Long> roleIds = roleIdsOf(loginId);
        if (roleIds.isEmpty()) {
            log.info("[diag] loginId={} roleIds=EMPTY", loginId);
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
            log.info("[diag] loginId={} roleIds={} permissionIds=EMPTY", loginId, roleIds);
            return List.of();
        }
        List<String> codes = sysPermissionMapper.selectByIds(permissionIds).stream()
                .filter(permission -> Integer.valueOf(1).equals(permission.getStatus()))
                .map(SysPermission::getCode)
                .distinct()
                .toList();
        log.info("[diag] loginId={} roleIds={} permissionIds={} codes={}", loginId, roleIds, permissionIds, codes);
        return codes;
    }

    @Override
    public List<String> getRoleList(Object loginId, String loginType) {
        List<Long> roleIds = roleIdsOf(loginId);
        if (roleIds.isEmpty()) {
            return List.of();
        }
        return sysRoleMapper.selectByIds(roleIds).stream()
                .filter(role -> Integer.valueOf(1).equals(role.getStatus()))
                .map(SysRole::getCode)
                .distinct()
                .toList();
    }

    private List<Long> roleIdsOf(Object loginId) {
        Long userId = Long.valueOf(String.valueOf(loginId));
        return sysUserRoleMapper
                .selectList(Wrappers.<SysUserRole>lambdaQuery().eq(SysUserRole::getUserId, userId))
                .stream()
                .map(SysUserRole::getRoleId)
                .distinct()
                .toList();
    }
}
