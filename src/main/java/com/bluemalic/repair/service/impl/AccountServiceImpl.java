package com.bluemalic.repair.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bluemalic.repair.common.BizException;
import com.bluemalic.repair.common.ErrorCode;
import com.bluemalic.repair.entity.SysRole;
import com.bluemalic.repair.entity.SysUser;
import com.bluemalic.repair.entity.SysUserRole;
import com.bluemalic.repair.mapper.SysRoleMapper;
import com.bluemalic.repair.mapper.SysUserMapper;
import com.bluemalic.repair.mapper.SysUserRoleMapper;
import com.bluemalic.repair.service.AccountService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

/**
 * 账号公共操作的实现。三条不变量与它们的理由见 {@link AccountService} 的类注释。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AccountServiceImpl implements AccountService {

    private static final int STATUS_ENABLED = 1;

    private final SysUserMapper sysUserMapper;
    private final SysUserRoleMapper sysUserRoleMapper;
    private final SysRoleMapper sysRoleMapper;
    private final PasswordEncoder passwordEncoder;

    @Override
    @Transactional
    public SysUser create(NewAccount spec) {
        SysUser user = new SysUser();
        user.setTenantId(spec.tenantId());
        user.setUsername(spec.username());
        // 口令只在这一处出现，既不回读也不打日志
        user.setPassword(passwordEncoder.encode(spec.rawPassword()));
        user.setRealName(spec.realName());
        user.setPhone(spec.phone());
        user.setUserType(spec.userType());
        user.setStatus(STATUS_ENABLED);
        user.setMustChangePassword(spec.mustChangePassword() ? 1 : 0);
        try {
            sysUserMapper.insert(user);
        } catch (DuplicateKeyException e) {
            // uk_tenant_username 兜底：并发下两个管理员建同一个学号/工号时，到这里变成明确的业务错误。
            // 对外不说"这是谁的账号"，只说这个登录名被占了
            throw new BizException(ErrorCode.PARAM_INVALID, spec.usernameLabel() + "已存在");
        }

        // 角色关联必须写：少了它，新账号能登录但拿不到任何权限码，每个接口都 403
        SysUserRole link = new SysUserRole();
        link.setUserId(user.getId());
        link.setRoleId(roleIdByCode(spec.roleCode()));
        sysUserRoleMapper.insert(link);
        return user;
    }

    @Override
    public void updateProfile(long userId, long tenantId, String realName, String phone) {
        int rows = sysUserMapper.update(null, Wrappers.<SysUser>lambdaUpdate()
                .eq(SysUser::getId, userId)
                .eq(SysUser::getTenantId, tenantId)
                .set(SysUser::getRealName, realName)
                .set(SysUser::getPhone, phone));
        requireUpdated(rows, "账号");
    }

    @Override
    public void changeStatus(long userId, long tenantId, int status, String accountLabel) {
        int rows = sysUserMapper.update(null, Wrappers.<SysUser>lambdaUpdate()
                .eq(SysUser::getId, userId)
                .eq(SysUser::getTenantId, tenantId)
                .set(SysUser::getStatus, status));
        requireUpdated(rows, accountLabel);

        if (Integer.valueOf(STATUS_ENABLED).equals(status)) {
            return;
        }
        // 停用只写库是不够的：登录态在 Redis，不受 status 影响，他手上的 token 还能用满 7 天。
        // isLogin 先判断是必要的——**从没登录过的账号直接 kickout 会抛异常**，
        // 而"新建完立刻停用"是很常见的操作顺序。
        if (StpUtil.isLogin(userId)) {
            StpUtil.kickout(userId);
            log.info("停用账号并踢下线 userId={} 操作人={}", userId, StpUtil.getLoginIdAsLong());
        } else {
            log.info("停用账号（该账号当前无登录态）userId={} 操作人={}", userId, StpUtil.getLoginIdAsLong());
        }
    }

    @Override
    public void resetPassword(long userId, long tenantId, String rawPassword, boolean mustChangePassword) {
        int rows = sysUserMapper.update(null, Wrappers.<SysUser>lambdaUpdate()
                .eq(SysUser::getId, userId)
                .eq(SysUser::getTenantId, tenantId)
                .set(SysUser::getPassword, passwordEncoder.encode(rawPassword))
                .set(SysUser::getMustChangePassword, mustChangePassword ? 1 : 0));
        requireUpdated(rows, "账号");
        // 只记"谁改了哪个账号的口令"，口令本身不进日志
        log.info("重置账号口令 userId={} 需强制改密={} 操作人={}",
                userId, mustChangePassword, StpUtil.getLoginIdAsLong());
    }

    @Override
    public void clearMustChangePassword(long userId, long tenantId) {
        sysUserMapper.update(null, Wrappers.<SysUser>lambdaUpdate()
                .eq(SysUser::getId, userId)
                .eq(SysUser::getTenantId, tenantId)
                .set(SysUser::getMustChangePassword, 0));
    }

    @Override
    public SysUser require(long userId, long tenantId, int userType, String accountLabel) {
        SysUser user = sysUserMapper.selectOne(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getId, userId)
                .eq(SysUser::getTenantId, tenantId));
        if (user == null || !Integer.valueOf(userType).equals(user.getUserType())) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, accountLabel + "不存在");
        }
        return user;
    }

    @Override
    public boolean usernameExists(long tenantId, String username) {
        if (!StringUtils.hasText(username)) {
            return false;
        }
        Long count = sysUserMapper.selectCount(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getTenantId, tenantId)
                .eq(SysUser::getUsername, username));
        return count != null && count > 0;
    }

    private void requireUpdated(int rows, String accountLabel) {
        if (rows == 0) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, accountLabel + "不存在");
        }
    }

    /**
     * 按角色码取角色 ID，**不写死数字**：写死的话角色 ID 一旦调整，就会静默造出
     * "能登录但每个接口都 403"的账号，那种问题排查起来非常费时间。
     *
     * <p>当前角色是全局的（种子数据 {@code sys_role.tenant_id = 0}），所以只按码查；
     * 将来若变成各租户自带角色，这里就是唯一需要改的地方。
     */
    private long roleIdByCode(String roleCode) {
        return sysRoleMapper.selectList(Wrappers.<SysRole>lambdaQuery()
                        .eq(SysRole::getCode, roleCode))
                .stream()
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.SYSTEM_ERROR, "角色数据缺失：" + roleCode))
                .getId();
    }
}
