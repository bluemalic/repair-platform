package com.bluemalic.repair.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bluemalic.repair.common.BizException;
import com.bluemalic.repair.common.ErrorCode;
import com.bluemalic.repair.common.UserType;
import com.bluemalic.repair.entity.SysUser;
import com.bluemalic.repair.entity.Tenant;
import com.bluemalic.repair.mapper.SysUserMapper;
import com.bluemalic.repair.mapper.TenantMapper;
import com.bluemalic.repair.service.TenantQuotaService;
import com.bluemalic.repair.tenancy.TenantRoutingContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 租户账号配额的实现（阶段 5 · ADR-020）。
 *
 * <p>规则只剩两条，都写在这里，别在调用方重复判断：
 * <ul>
 *   <li>上限 {@code <= 0} 表示**不限**（平台自用租户可以不设上限）</li>
 *   <li>已用 = {@code deleted = 0} 的全部账号，**含停用**（停用不是删除，随时可能被启用）</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantQuotaServiceImpl implements TenantQuotaService {

    private final TenantMapper tenantMapper;
    private final SysUserMapper sysUserMapper;

    @Override
    public void requireStudentSeats(long tenantId, int adding) {
        requireSeats(tenantId, UserType.STUDENT, adding);
    }

    @Override
    public void requireWorkerSeats(long tenantId, int adding) {
        requireSeats(tenantId, UserType.WORKER, adding);
    }

    private void requireSeats(long tenantId, UserType type, int adding) {
        if (adding <= 0) {
            return;
        }
        // 配额读**平台库**（`tenant` 表只在平台库），账号数**在本租户库里数**（账号跟着学校走）。
        // 两个库、两次读，这里合并不了——`sys_user` 与 `tenant` 从物理上就是分开的（ADR-021）
        Tenant tenant = TenantRoutingContext.callControl(() -> tenantMapper.selectById(tenantId));
        if (tenant == null) {
            throw new BizException(ErrorCode.TENANT_NOT_FOUND);
        }
        int max = type == UserType.STUDENT ? tenant.getMaxStudents() : tenant.getMaxWorkers();
        if (max <= 0) {
            return;
        }
        long used = sysUserMapper.selectCount(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getTenantId, tenantId)
                .eq(SysUser::getUserType, type.getCode()));
        if (used + adding > max) {
            log.warn("租户配额不足 tenantId={} 类型={} 已用={} 本次={} 上限={}",
                    tenantId, type, used, adding, max);
            throw new BizException(ErrorCode.TENANT_QUOTA_EXCEEDED,
                    type.getDesc() + "账号配额不足：已用 " + used + " / 上限 " + max
                            + "，本次需要 " + adding + " 个。请联系平台运营调整配额");
        }
    }
}
