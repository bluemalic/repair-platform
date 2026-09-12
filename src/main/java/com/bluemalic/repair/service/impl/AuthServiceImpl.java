package com.bluemalic.repair.service.impl;

import cn.dev33.satoken.stp.SaTokenInfo;
import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bluemalic.repair.common.BizException;
import com.bluemalic.repair.common.ErrorCode;
import com.bluemalic.repair.dto.LoginDTO;
import com.bluemalic.repair.entity.SysUser;
import com.bluemalic.repair.entity.Tenant;
import com.bluemalic.repair.mapper.SysUserMapper;
import com.bluemalic.repair.mapper.TenantMapper;
import com.bluemalic.repair.service.AuthService;
import com.bluemalic.repair.vo.CurrentUserVO;
import com.bluemalic.repair.vo.LoginVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

/**
 * 登录 / 注销 / 当前用户。
 *
 * <p>失败一律抛 {@link BizException}，由 GlobalExceptionHandler 统一转成返回体——
 * 不在业务代码里 try-catch 后 return 错误码（AGENTS 第 5 节）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthServiceImpl implements AuthService {

    private final TenantMapper tenantMapper;
    private final SysUserMapper sysUserMapper;
    private final PasswordEncoder passwordEncoder;
    /** 复用同一个权限查询实现，避免"当前用户看到的权限"和"鉴权用的权限"两套口径 */
    private final StpInterface stpInterface;

    @Override
    public LoginVO login(LoginDTO dto) {
        Tenant tenant = tenantMapper.selectOne(Wrappers.<Tenant>lambdaQuery()
                .eq(Tenant::getCode, dto.getTenantCode()));
        if (tenant == null || !Integer.valueOf(1).equals(tenant.getStatus())) {
            throw new BizException(ErrorCode.TENANT_NOT_FOUND);
        }

        SysUser user = sysUserMapper.selectOne(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getTenantId, tenant.getId())
                .eq(SysUser::getUsername, dto.getUsername()));
        // 账号不存在与密码错误返回同一个错误码：不向尝试者透露"这个账号是否存在"
        if (user == null || !passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            throw new BizException(ErrorCode.LOGIN_FAILED);
        }
        // 状态检查放在密码校验之后，同样是为了不泄露账号是否存在
        if (!Integer.valueOf(1).equals(user.getStatus())) {
            throw new BizException(ErrorCode.ACCOUNT_DISABLED);
        }

        StpUtil.login(user.getId());
        SaTokenInfo tokenInfo = StpUtil.getTokenInfo();

        LoginVO vo = new LoginVO();
        vo.setTokenName(tokenInfo.getTokenName());
        vo.setTokenValue(tokenInfo.getTokenValue());
        vo.setUserId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setRealName(user.getRealName());
        vo.setUserType(user.getUserType());

        // 不打印账号口令等敏感信息，只记定位问题需要的 ID
        log.info("登录成功 userId={} tenantId={} userType={}",
                user.getId(), tenant.getId(), user.getUserType());
        return vo;
    }

    @Override
    public void logout() {
        Long userId = StpUtil.getLoginIdAsLong();
        StpUtil.logout();
        log.info("登出 userId={}", userId);
    }

    @Override
    public CurrentUserVO currentUser() {
        long userId = StpUtil.getLoginIdAsLong();
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            // 极端情况：登录态有效但用户已被删除
            throw new BizException(ErrorCode.NOT_LOGIN);
        }
        String loginType = StpUtil.getLoginType();
        CurrentUserVO vo = new CurrentUserVO();
        vo.setUserId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setRealName(user.getRealName());
        vo.setUserType(user.getUserType());
        vo.setRoles(stpInterface.getRoleList(userId, loginType));
        vo.setPermissions(stpInterface.getPermissionList(userId, loginType));
        return vo;
    }
}
