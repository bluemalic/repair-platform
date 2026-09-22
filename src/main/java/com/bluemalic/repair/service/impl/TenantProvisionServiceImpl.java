package com.bluemalic.repair.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bluemalic.repair.common.BizException;
import com.bluemalic.repair.common.ErrorCode;
import com.bluemalic.repair.common.Paging;
import com.bluemalic.repair.common.UserType;
import com.bluemalic.repair.converter.TenantConverter;
import com.bluemalic.repair.dto.TenantAdminCreateDTO;
import com.bluemalic.repair.dto.TenantCreateDTO;
import com.bluemalic.repair.dto.TenantUpdateDTO;
import com.bluemalic.repair.entity.SysUser;
import com.bluemalic.repair.entity.Tenant;
import com.bluemalic.repair.mapper.SysUserMapper;
import com.bluemalic.repair.mapper.TenantMapper;
import com.bluemalic.repair.service.AccountService;
import com.bluemalic.repair.service.TenantProvisionService;
import com.bluemalic.repair.vo.PageResult;
import com.bluemalic.repair.vo.TenantAdminVO;
import com.bluemalic.repair.vo.TenantVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 平台运营的实现。跨租户这件事与它带来的边界要求见 {@link TenantProvisionService} 的类注释。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantProvisionServiceImpl implements TenantProvisionService {

    private static final int STATUS_ENABLED = 1;

    /** 平台自身在 {@code tenant} 表里的 id。它不是一个学校，所有租户操作都要把它排除掉。 */
    private static final long PLATFORM_TENANT_ID = 0L;

    private static final String TENANT_LABEL = "租户";
    private static final String ADMIN_LABEL = "管理员";

    private final TenantMapper tenantMapper;
    private final SysUserMapper sysUserMapper;
    private final AccountService accountService;

    @Override
    public PageResult<TenantVO> page(long pageNum, long pageSize, Integer status, String keyword) {
        var query = Wrappers.<Tenant>lambdaQuery()
                // 平台自身不是学校：不排除的话它会出现在租户列表里，还会被当成"可以停用的租户"
                .ne(Tenant::getId, PLATFORM_TENANT_ID)
                .eq(status != null, Tenant::getStatus, status);
        if (StringUtils.hasText(keyword)) {
            query.and(w -> w.like(Tenant::getName, keyword).or().like(Tenant::getCode, keyword));
        }

        Page<Tenant> page = tenantMapper.selectPage(
                new Page<>(Paging.clamp(pageNum), Paging.clamp(pageSize)),
                query.orderByDesc(Tenant::getId));

        Page<TenantVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(page.getRecords().stream().map(TenantConverter::toVO).toList());
        return PageResult.of(voPage);
    }

    @Override
    @Transactional
    public TenantVO provision(TenantCreateDTO dto) {
        Tenant tenant = new Tenant();
        tenant.setName(dto.getName());
        tenant.setCode(dto.getCode());
        tenant.setContact(dto.getContact());
        tenant.setPhone(dto.getPhone());
        tenant.setStatus(STATUS_ENABLED);
        try {
            tenantMapper.insert(tenant);
        } catch (DuplicateKeyException e) {
            // uk_code 兜底：并发开通同一个编码时，到这里变成明确的业务错误而不是 500
            throw new BizException(ErrorCode.PARAM_INVALID, "学校编码已存在");
        }

        // 与租户同一个事务：只建出租户、没有管理员的话，那是个谁也进不去的空壳
        accountService.create(new AccountService.NewAccount(tenant.getId(), dto.getAdminUsername(),
                dto.getAdminPassword(), dto.getAdminRealName(), dto.getAdminPhone(),
                UserType.ADMIN.getCode(), UserType.ADMIN.getRoleCode(), true, "管理员登录名"));

        log.info("开通租户 tenantId={} code={} 操作人={}",
                tenant.getId(), tenant.getCode(), StpUtil.getLoginIdAsLong());
        // 回查一次：create_time 是数据库默认值填的，插入后的实体里那个字段还是 null，
        // 直接转 VO 会让开通接口的响应缺一列（列表接口里却有），前端得为同一个字段写两种判断
        return TenantConverter.toVO(tenantMapper.selectById(tenant.getId()));
    }

    @Override
    public void update(long tenantId, TenantUpdateDTO dto) {
        requireTenant(tenantId);
        int rows = tenantMapper.update(null, Wrappers.<Tenant>lambdaUpdate()
                .eq(Tenant::getId, tenantId)
                .set(Tenant::getName, dto.getName())
                .set(Tenant::getContact, dto.getContact())
                .set(Tenant::getPhone, dto.getPhone()));
        requireUpdated(rows);
        log.info("修改租户资料 tenantId={} 操作人={}", tenantId, StpUtil.getLoginIdAsLong());
    }

    @Override
    public void changeStatus(long tenantId, int status) {
        requireTenant(tenantId);
        int rows = tenantMapper.update(null, Wrappers.<Tenant>lambdaUpdate()
                .eq(Tenant::getId, tenantId)
                .set(Tenant::getStatus, status));
        requireUpdated(rows);

        if (Integer.valueOf(STATUS_ENABLED).equals(status)) {
            log.info("启用租户 tenantId={} 操作人={}", tenantId, StpUtil.getLoginIdAsLong());
            return;
        }
        // **先改库再踢人**，顺序不能反：反过来的话，踢人过程中新登录的用户读到的还是旧状态、
        // 能登进来，然后一直活到 token 过期。先改库则登录接口立刻开始拒绝新登录。
        int online = accountService.kickoutAllOfTenant(tenantId);
        log.info("停用租户 tenantId={} 踢下线在线数={} 操作人={}",
                tenantId, online, StpUtil.getLoginIdAsLong());
    }

    @Override
    public List<TenantAdminVO> admins(long tenantId) {
        requireTenant(tenantId);
        return sysUserMapper.selectList(Wrappers.<SysUser>lambdaQuery()
                        .eq(SysUser::getTenantId, tenantId)
                        .eq(SysUser::getUserType, UserType.ADMIN.getCode())
                        .orderByAsc(SysUser::getId))
                .stream()
                .map(TenantConverter::toAdminVO)
                .toList();
    }

    @Override
    public TenantAdminVO addAdmin(long tenantId, TenantAdminCreateDTO dto) {
        requireTenant(tenantId);
        SysUser admin = accountService.create(new AccountService.NewAccount(tenantId, dto.getUsername(),
                dto.getPassword(), dto.getRealName(), dto.getPhone(),
                UserType.ADMIN.getCode(), UserType.ADMIN.getRoleCode(), true, "登录名"));
        log.info("新增租户管理员 tenantId={} userId={} username={} 操作人={}",
                tenantId, admin.getId(), admin.getUsername(), StpUtil.getLoginIdAsLong());
        return TenantConverter.toAdminVO(admin);
    }

    @Override
    public void resetAdminPassword(long tenantId, long userId, String rawPassword) {
        requireTenant(tenantId);
        // require 校验"这个 id 是本租户的后勤管理员"：路径里的租户与用户必须对得上，
        // 否则平台能借 A 租户的路径改 B 租户的账号口令
        accountService.require(userId, tenantId, UserType.ADMIN.getCode(), ADMIN_LABEL);
        accountService.resetPassword(userId, tenantId, rawPassword, true);
        log.info("平台重置租户管理员口令 tenantId={} userId={} 操作人={}",
                tenantId, userId, StpUtil.getLoginIdAsLong());
    }

    /**
     * 取一个**真实存在的学校**，不存在（或就是平台自身）一律说"租户不存在"。
     *
     * <p>排除 {@code id = 0} 是这里最要紧的一行：没有它，平台就能停用自己、把自己的管理员
     * 口令重置掉——而它没有任何别的入口能把自己救回来。
     */
    private Tenant requireTenant(long tenantId) {
        Tenant tenant = tenantMapper.selectOne(Wrappers.<Tenant>lambdaQuery()
                .eq(Tenant::getId, tenantId)
                .ne(Tenant::getId, PLATFORM_TENANT_ID));
        if (tenant == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, TENANT_LABEL + "不存在");
        }
        return tenant;
    }

    private void requireUpdated(int rows) {
        if (rows == 0) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, TENANT_LABEL + "不存在");
        }
    }
}
