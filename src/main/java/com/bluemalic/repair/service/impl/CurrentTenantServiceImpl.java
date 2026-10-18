package com.bluemalic.repair.service.impl;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import com.bluemalic.repair.common.BizException;
import com.bluemalic.repair.common.ErrorCode;
import com.bluemalic.repair.entity.SysUser;
import com.bluemalic.repair.mapper.SysUserMapper;
import com.bluemalic.repair.service.CurrentTenantService;
import com.bluemalic.repair.tenancy.TenantRoutingContext;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class CurrentTenantServiceImpl implements CurrentTenantService {

    private final SysUserMapper sysUserMapper;

    @Override
    public void bind(Long tenantId) {
        StpUtil.getSession().set(SESSION_TENANT_KEY, tenantId);
    }

    @Override
    public Long tenantIdOrNull() {
        Object loginId;
        try {
            loginId = StpUtil.getLoginIdDefaultNull();
        } catch (Exception e) {
            // SaTokenContext 未初始化（纯 Mapper 调用 / 定时任务的"系统上下文"）→ 视为无登录态
            return null;
        }
        if (loginId == null) {
            return null;
        }
        Long userId = Long.parseLong(String.valueOf(loginId));

        SaSession session = StpUtil.getSessionByLoginId(userId, false);
        if (session != null) {
            Object cached = session.get(SESSION_TENANT_KEY);
            if (cached != null) {
                return Long.parseLong(String.valueOf(cached));
            }
        }
        // 兜底：登录时没写进 Session 的旧 token（或别处直接登录的场景）→ 回查一次并回填，下次就不查了。
        //
        // 这一次读**钉在平台库上**（阶段 6 · ADR-021）：能走到这里说明租户还没定位到，
        // 而"这个用户属于哪个租户"要查的正是平台库里的账号行。存量租户的账号在平台库，回查得到；
        // 已经搬到自己库的学校，它的账号回查不到（返回 null）——这不是静默走错库：
        // 租户为 null 时数据权限拦截器只注入角色条件（学生 / 师傅看到的仍是空集），
        // 需要租户的地方（如 requireTenantId）直接抛 10002。**这里没有"猜一个租户"的分支。**
        SysUser user = TenantRoutingContext.callControl(() -> sysUserMapper.selectById(userId));
        if (user == null) {
            return null;
        }
        if (session != null) {
            session.set(SESSION_TENANT_KEY, user.getTenantId());
        }
        return user.getTenantId();
    }

    @Override
    public Long requireTenantId() {
        Long tenantId = tenantIdOrNull();
        if (tenantId == null) {
            throw new BizException(ErrorCode.NOT_LOGIN);
        }
        return tenantId;
    }
}