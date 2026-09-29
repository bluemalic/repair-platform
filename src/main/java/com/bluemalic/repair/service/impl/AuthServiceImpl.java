package com.bluemalic.repair.service.impl;

import cn.dev33.satoken.stp.SaTokenInfo;
import cn.dev33.satoken.stp.StpInterface;
import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bluemalic.repair.common.AuditAction;
import com.bluemalic.repair.common.AuditTarget;
import com.bluemalic.repair.common.BizException;
import com.bluemalic.repair.common.ErrorCode;
import com.bluemalic.repair.common.RateLimiter;
import com.bluemalic.repair.config.RateLimitRule;
import com.bluemalic.repair.dto.LoginDTO;
import com.bluemalic.repair.dto.PasswordChangeDTO;
import com.bluemalic.repair.interceptor.MustChangePasswordInterceptor;
import com.bluemalic.repair.interceptor.PlatformScopeInterceptor;
import com.bluemalic.repair.entity.SysUser;
import com.bluemalic.repair.entity.Tenant;
import com.bluemalic.repair.mapper.SysUserMapper;
import com.bluemalic.repair.mapper.TenantMapper;
import com.bluemalic.repair.service.AuthService;
import com.bluemalic.repair.service.AuditService;
import com.bluemalic.repair.service.CurrentTenantService;
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
    private final CurrentTenantService currentTenantService;
    private final RateLimiter rateLimiter;
    private final RateLimitRule rateLimitRule;

    private final AuditService auditService;

    @Override
    public LoginVO login(LoginDTO dto) {
        Tenant tenant = tenantMapper.selectOne(Wrappers.<Tenant>lambdaQuery()
                .eq(Tenant::getCode, dto.getTenantCode()));
        if (tenant == null || !Integer.valueOf(1).equals(tenant.getStatus())) {
            // 租户都不存在 / 已停用：**不记审计**——这条记录没有"谁的表"能放（审计是按租户查的），
            // 这类探测留在服务器的 WARN 日志里（docs/01 §4.4 的"明确不记"）
            throw new BizException(ErrorCode.TENANT_NOT_FOUND);
        }
        // 限流拦下的也不单独记审计：它是失败的延续，同一次爆破会连续触发、逐条记只会刷屏；
        // 每次**失败**都已经记了，看次数就够（docs/01 §4.4）
        requireLoginAttemptAllowed(tenant.getId(), dto.getUsername());

        SysUser user = sysUserMapper.selectOne(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getTenantId, tenant.getId())
                .eq(SysUser::getUsername, dto.getUsername()));
        // 账号不存在与密码错误返回同一个错误码：不向尝试者透露"这个账号是否存在"
        if (user == null || !passwordEncoder.matches(dto.getPassword(), user.getPassword())) {
            // 审计里**写清**是哪种（看的人是本租户后勤管理，他本来就看得见账号列表），
            // 而接口响应仍然不区分——理由见 docs/01 §4.4。detail 里绝不带口令原文
            auditService.recordLoginFailure(tenant.getId(), user == null ? null : user.getId(), dto.getUsername(),
                    AuditAction.LOGIN_FAILED, user == null ? "账号不存在" : "口令错误");
            throw new BizException(ErrorCode.LOGIN_FAILED);
        }
        // 状态检查放在密码校验之后，同样是为了不泄露账号是否存在
        if (!Integer.valueOf(1).equals(user.getStatus())) {
            auditService.recordLoginFailure(tenant.getId(), user.getId(), user.getUsername(),
                    AuditAction.LOGIN_DISABLED, "账号已停用");
            throw new BizException(ErrorCode.ACCOUNT_DISABLED);
        }

        StpUtil.login(user.getId());
        // 把租户写进 Session：数据权限拦截器每个请求都要用，从 Session 读可省掉每请求一次查库
        currentTenantService.bind(tenant.getId());
        // 待改密标记也放 Session：拦截器每个请求都要读它，放这里同样省掉每请求一次查库
        boolean mustChangePassword = Integer.valueOf(1).equals(user.getMustChangePassword());
        StpUtil.getSession().set(MustChangePasswordInterceptor.SESSION_KEY, mustChangePassword);
        // 用户类型同理：平台域收口拦截器每个请求都要判"这是不是平台账号"，
        // 而 StpInterface 查角色/权限是每次校验都查库的（见 StpInterfaceImpl 的类注释）
        StpUtil.getSession().set(PlatformScopeInterceptor.SESSION_USER_TYPE_KEY, user.getUserType());
        SaTokenInfo tokenInfo = StpUtil.getTokenInfo();

        LoginVO vo = new LoginVO();
        vo.setTokenName(tokenInfo.getTokenName());
        vo.setTokenValue(tokenInfo.getTokenValue());
        vo.setUserId(user.getId());
        vo.setUsername(user.getUsername());
        vo.setRealName(user.getRealName());
        vo.setUserType(user.getUserType());
        vo.setMustChangePassword(mustChangePassword);

        // 不打印账号口令等敏感信息，只记定位问题需要的 ID
        log.info("登录成功 userId={} tenantId={} userType={}",
                user.getId(), tenant.getId(), user.getUserType());
        // 登录成功也入审计（docs/01 §4.4）：按「账号 + 天 + 来源 IP」去重，同一天同一处只留一条。
        // 写失败只记 ERROR、不影响这次登录——它是全项目唯一"审计失败不阻断业务"的地方
        auditService.recordLoginSuccess(tenant.getId(), user.getId(), user.getUsername());
        return vo;
    }

    @Override
    public void logout() {
        Long userId = StpUtil.getLoginIdAsLong();
        StpUtil.logout();
        log.info("登出 userId={}", userId);
    }

    /**
     * 登录接口的限流：按「租户 + 账号」计数，超限抛 10004。
     *
     * <p>放在这里而不是拦截器里，是因为计数维度是账号、而账号在请求体里（理由见
     * {@link RateLimiter} 的类注释）；按 IP 计在校园网里会误伤 NAT 出口后面的正常用户，
     * 理由见 {@link RateLimitRule}。
     *
     * <p><b>计数发生在口令校验之前</b>：先计数再验密，脚本用对密码还是错密码都同样消耗额度，
     * 不能靠"只统计失败"来给爆破者省额度。代价是被人恶意刷满时，这个账号在窗口内（默认 60 秒）
     * 也登不上——这是"按账号计数"必然带来的取舍，窗口短所以影响有限。
     */
    private void requireLoginAttemptAllowed(Long tenantId, String username) {
        String key = RateLimiter.KEY_PREFIX + "login:" + tenantId + ":" + username;
        Long count = rateLimiter.increment(key, rateLimitRule.getWindowSeconds());
        if (count != null && count > rateLimitRule.getLoginMaxRequests()) {
            // 不记账号名：学号是个人信息，排查靠租户 + 计数就够定位（AGENTS 第 5 节第 8 条）
            log.warn("登录触发限流 tenantId={} 第 {} 次请求，阈值 {}/{}s",
                    tenantId, count, rateLimitRule.getLoginMaxRequests(), rateLimitRule.getWindowSeconds());
            throw new BizException(ErrorCode.TOO_MANY_REQUESTS);
        }
    }

    @Override
    public void changePassword(PasswordChangeDTO dto) {
        long userId = StpUtil.getLoginIdAsLong();
        SysUser user = sysUserMapper.selectById(userId);
        if (user == null) {
            // 与 currentUser() 同一处理：登录态有效但用户已被删除
            throw new BizException(ErrorCode.NOT_LOGIN);
        }
        if (!passwordEncoder.matches(dto.getOldPassword(), user.getPassword())) {
            // 复用 30001（用户名或密码错误），但换成改密场景下说得通的文案
            throw new BizException(ErrorCode.LOGIN_FAILED, "当前密码不正确");
        }
        if (dto.getOldPassword().equals(dto.getNewPassword())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "新密码不能与当前密码相同");
        }

        SysUser update = new SysUser();
        update.setId(userId);
        update.setPassword(passwordEncoder.encode(dto.getNewPassword()));
        // 改密即"不再是初始口令"。这一步放在同一个更新里：分成两次写就可能出现
        // "密码变了、标记还在"的中间态，用户改完还是被拦在改密页上。
        update.setMustChangePassword(0);
        sysUserMapper.updateById(update);

        // 审计（账号安全事件）**必须写在 logout 之前**：审计要取当前租户与操作人，
        // 而下面那句 logout 会把这次请求的登录态一起注销掉——写在其后会让 tenant_id 取到 null，
        // 连带把整个改密操作拖失败（这条是写测试时才发现的，见 AuditLogTest 的注释）。
        // 也正因为审计与业务同事务，顺序写错不是"少一条记录"，而是"功能直接不可用"——这是刻意的
        auditService.record(AuditAction.PASSWORD_CHANGE_SELF, AuditTarget.ACCOUNT, userId, user.getUsername(),
                "本人修改登录口令（改后所有会话失效，需重新登录）");

        // 改密之后让该账号的所有会话失效，包括正在发起这次改密的会话。
        // 只失效其他端是不够的：改密的动机之一就是"怀疑账号被别人用着"，
        // 而"当前这个会话是可信的"这个前提并不成立——它可能正是被盗用的那一个。
        // 代价是改完要重新登录一次，正好也验证了新密码记得住。
        // 顺带：拦截器读的"待改密"标记存在 Session 里，会话一注销它就没了，
        // 不需要单独清——这也是把标记放 Session 而不是每请求查库带来的好处。
        StpUtil.logout(userId);

        log.info("修改密码 userId={} 操作人={}", userId, userId);
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
