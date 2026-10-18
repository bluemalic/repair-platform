package com.bluemalic.repair.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bluemalic.repair.common.AuditAction;
import com.bluemalic.repair.common.AuditTarget;
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
import com.bluemalic.repair.mapper.TenantUsageMapper;
import com.bluemalic.repair.service.AccountService;
import com.bluemalic.repair.service.AuditService;
import com.bluemalic.repair.service.TenantProvisionService;
import com.bluemalic.repair.tenancy.TenantDatabaseProvisioner;
import com.bluemalic.repair.tenancy.TenantRoutingContext;
import com.bluemalic.repair.tenancy.TenantSchemaCatalog;
import com.bluemalic.repair.vo.PageResult;
import com.bluemalic.repair.vo.TenantAdminVO;
import com.bluemalic.repair.vo.TenantUsageVO;
import com.bluemalic.repair.vo.TenantVO;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
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

    /** 与 `V11__tenant_quota.sql` 的列默认值保持一致 */
    private static final int DEFAULT_MAX_STUDENTS = 200;
    private static final int DEFAULT_MAX_WORKERS = 20;

    /** 平台自身在 {@code tenant} 表里的 id。它不是一个学校，所有租户操作都要把它排除掉。 */
    private static final long PLATFORM_TENANT_ID = 0L;

    private static final String TENANT_LABEL = "租户";
    private static final String ADMIN_LABEL = "管理员";

    /** 本页租户里某类账号的数量；tenantIds 为空时不查库 */
    private Map<Long, Long> usageByType(List<Long> tenantIds, UserType type) {
        if (tenantIds.isEmpty()) {
            return Map.of();
        }
        return tenantUsageMapper.countByTenant(tenantIds).stream()
                .filter(usage -> Integer.valueOf(type.getCode()).equals(usage.getUserType()))
                .collect(Collectors.toMap(TenantUsageVO::getTenantId, TenantUsageVO::getAccountCount));
    }

    private final TenantMapper tenantMapper;
    private final SysUserMapper sysUserMapper;
    private final TenantUsageMapper tenantUsageMapper;
    private final AccountService accountService;

    private final AuditService auditService;

    /** 建库 / 跑租户库迁移 / 只读账号授权（阶段 6 · ADR-021） */
    private final TenantDatabaseProvisioner databaseProvisioner;

    /** tenantId → 库名：开通完要作废缓存，否则这个学校的第一批请求还会走平台库 */
    private final TenantSchemaCatalog schemaCatalog;

    /** 开通要跨两个库，所以事务边界得显式划（平台上没有 XA；与 DemoResetServiceImpl 同一手法） */
    private final PlatformTransactionManager transactionManager;

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

        // 配额用量：**还没搬的学校**在平台库一次分组查完；**已经搬到自己库的学校**得去它自己的库里数
        // （账号跟着学校走，平台库里没有它们的行）——所以这里是"一批 + 零散几个"的混合口径（ADR-021）
        List<Long> legacyIds = page.getRecords().stream()
                .filter(tenant -> tenant.getDbSchema() == null)
                .map(Tenant::getId)
                .toList();
        Map<Long, Long> students = new HashMap<>(usageByType(legacyIds, UserType.STUDENT));
        Map<Long, Long> workers = new HashMap<>(usageByType(legacyIds, UserType.WORKER));
        for (Tenant tenant : page.getRecords()) {
            if (tenant.getDbSchema() == null) {
                continue;
            }
            students.put(tenant.getId(), countAccountsInTenant(tenant, UserType.STUDENT));
            workers.put(tenant.getId(), countAccountsInTenant(tenant, UserType.WORKER));
        }

        Page<TenantVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(page.getRecords().stream().map(tenant -> {
            TenantVO vo = TenantConverter.toVO(tenant);
            vo.setStudentCount(students.getOrDefault(tenant.getId(), 0L));
            vo.setWorkerCount(workers.getOrDefault(tenant.getId(), 0L));
            return vo;
        }).toList());
        return PageResult.of(voPage);
    }

    /**
     * 开通一所学校：建租户库 → 落租户行 → 在租户库里建它的第一个后勤管理员。
     *
     * <p><b>为什么不再是"一个事务"</b>（阶段 6 · ADR-021）：这三步里有两步在不同的 database 上
     * （租户行在平台库，管理员在租户库），MySQL 没有跨库的 XA，一个事务包不住。所以这里的结构是
     * **显式两步 + 明确的失败姿态**：
     *
     * <ol>
     *   <li>建库 + 跑租户库迁移：幂等，可以重跑，不参与事务（DDL 在 MySQL 里本来也不能回滚）；</li>
     *   <li>平台库一个事务：落租户行（带 `db_schema`）+ 审计；</li>
     *   <li>切到这所学校的库建首个管理员（它自己一个事务）。</li>
     * </ol>
     *
     * <p><b>第 3 步失败为什么不回滚第 2 步</b>：反过来的顺序（先建账号、再落租户行）会留下
     * 一个**永远登不进来的孤儿账号**（登录要先按编码找到学校），而且重试会撞"登录名已存在"；
     * 现在的顺序失败后留下的是"没有管理员的学校"——它在平台列表里看得见，补一个管理员就能用
     * （平台有 {@code POST /api/platform/tenants/{id}/admins} 这个现成入口），报错信息里也会这么说。
     *
     * <p><b>为什么标 {@code NOT_SUPPORTED}</b>：开通不能挂在调用方的事务里。调用方若已有事务，
     * 那条事务的连接已经借出去了（且在平台库上），而第 3 步要在**另一所学校自己的库**上写数据——
     * 挂在同一个事务里就会写成"进平台库"，{@code TenantRoutingContext} 对这种切换是直接拒绝的。
     * 显式挂起调用方事务，开通的事务边界就完全落在这个方法内部（这条对测试同样成立：
     * 跨库的流程没法塞进一个测试事务里）。
     */
    @Override
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public TenantVO provision(TenantCreateDTO dto) {
        // 1) 预检：编码与库名都必须是空的。库名由编码推导（连字符换下划线），所以
        //    `a-b` 与 `a_b` 会推出同一个库名——那是两个不同的学校写同一个库，必须在这里挡住
        String schema = databaseProvisioner.schemaFor(dto.getCode());
        requireCodeAndSchemaFree(dto.getCode(), schema);

        // 2) 建库 + 跑租户库迁移（幂等；失败即抛，此时还没有任何租户行落库）
        databaseProvisioner.createAndMigrate(schema);

        Tenant tenant = new Tenant();
        tenant.setName(dto.getName());
        tenant.setCode(dto.getCode());
        tenant.setDbSchema(schema);
        tenant.setContact(dto.getContact());
        tenant.setPhone(dto.getPhone());
        // 留空走默认（200 / 20，与 V11 的列默认值一致）：开通接口不必强迫平台运营填配额
        tenant.setMaxStudents(dto.getMaxStudents() == null ? DEFAULT_MAX_STUDENTS : dto.getMaxStudents());
        tenant.setMaxWorkers(dto.getMaxWorkers() == null ? DEFAULT_MAX_WORKERS : dto.getMaxWorkers());
        tenant.setStatus(STATUS_ENABLED);

        // 3) 平台库一个事务：租户行 + 审计。租户 id 是 MP 在 insert 前用雪花算法生成的，
        //    所以事务体里就能拿到它，下面建管理员、刷路由缓存都要用
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            try {
                tenantMapper.insert(tenant);
            } catch (DuplicateKeyException e) {
                // uk_code / uk_db_schema 兜底：并发开通同一个编码时，到这里变成明确的业务错误而不是 500
                throw new BizException(ErrorCode.PARAM_INVALID, "学校编码已存在");
            }
            // 平台运营自己的操作，租户记 0（docs/01 §4.4）
            auditService.record(AuditAction.TENANT_PROVISION, AuditTarget.TENANT, tenant.getId(), tenant.getName(),
                    "开通学校 " + tenant.getName() + "（编码 " + tenant.getCode() + "，库 " + tenant.getDbSchema()
                            + "），并创建首个管理员 " + dto.getAdminUsername());
        });
        // 路由键是按租户缓存的：不作废的话，这所学校的第一批请求还会被送到平台库
        schemaCatalog.refresh(tenant.getId());

        // 4) 在**这所学校自己的库**里建它的第一个管理员。这一步跨库，所以单独一个事务
        //    （AccountServiceImpl.create 自带 @Transactional，路由键已切过去，连接就是那个库的）
        try {
            TenantRoutingContext.run(schema, () -> accountService.create(new AccountService.NewAccount(
                    tenant.getId(), dto.getAdminUsername(), dto.getAdminPassword(), dto.getAdminRealName(),
                    dto.getAdminPhone(), UserType.ADMIN.getCode(), UserType.ADMIN.getRoleCode(), true, "管理员登录名")));
        } catch (RuntimeException e) {
            log.error("学校已开通但首个管理员创建失败 tenantId={} schema={} username={}",
                    tenant.getId(), schema, dto.getAdminUsername(), e);
            throw new BizException(ErrorCode.SYSTEM_ERROR,
                    "学校已开通，但首个管理员创建失败（" + e.getMessage() + "）。"
                            + "它现在还不能用：请在租户列表里为它添加一个管理员");
        }

        // 5) 只读账号（AI 问数）的授权要把新库纳入进来；失败只记日志，不影响开通
        databaseProvisioner.ensureAiReadOnlyOnAllDatabases();

        log.info("开通学校 tenantId={} code={} schema={} 操作人={}",
                tenant.getId(), tenant.getCode(), schema, StpUtil.getLoginIdAsLong());
        // 回查一次：create_time 是数据库默认值填的，插入后的实体里那个字段还是 null，
        // 直接转 VO 会让开通接口的响应缺一列（列表接口里却有），前端得为同一个字段写两种判断
        TenantVO vo = TenantConverter.toVO(tenantMapper.selectById(tenant.getId()));
        // 新租户只有第一个管理员，学生 / 维修工都是 0
        vo.setStudentCount(0L);
        vo.setWorkerCount(0L);
        return vo;
    }

    /** 编码与库名都要是空的；库名冲突单独说清原因（两个编码可能推出同一个库名） */
    private void requireCodeAndSchemaFree(String code, String schema) {
        Long byCode = TenantRoutingContext.callControl(() -> tenantMapper.selectCount(
                Wrappers.<Tenant>lambdaQuery().eq(Tenant::getCode, code)));
        if (byCode != null && byCode > 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "学校编码已存在");
        }
        Long bySchema = TenantRoutingContext.callControl(() -> tenantMapper.selectCount(
                Wrappers.<Tenant>lambdaQuery().eq(Tenant::getDbSchema, schema)));
        if (bySchema != null && bySchema > 0) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "这个编码推出的库名 " + schema + " 已被另一所学校占用（连字符与下划线等价），请换一个编码");
        }
    }

    /** 已经搬到自己库的学校：账号数在它自己的库里数（平台列表一页最多几十个学校，且不是热点路径） */
    private long countAccountsInTenant(Tenant tenant, UserType type) {
        return TenantRoutingContext.call(routingKeyOf(tenant), () -> sysUserMapper.selectCount(
                Wrappers.<SysUser>lambdaQuery()
                        .eq(SysUser::getTenantId, tenant.getId())
                        .eq(SysUser::getUserType, type.getCode())));
    }

    /** 租户行的 db_schema → 路由键（null = 还没搬，数据仍在平台库） */
    private static String routingKeyOf(Tenant tenant) {
        return tenant.getDbSchema() == null ? TenantRoutingContext.CONTROL : tenant.getDbSchema();
    }

    @Override
    public void update(long tenantId, TenantUpdateDTO dto) {
        Tenant before = requireTenant(tenantId);
        int rows = tenantMapper.update(null, Wrappers.<Tenant>lambdaUpdate()
                .eq(Tenant::getId, tenantId)
                .set(Tenant::getName, dto.getName())
                .set(Tenant::getContact, dto.getContact())
                .set(Tenant::getPhone, dto.getPhone())
                .set(dto.getMaxStudents() != null, Tenant::getMaxStudents, dto.getMaxStudents())
                .set(dto.getMaxWorkers() != null, Tenant::getMaxWorkers, dto.getMaxWorkers()));
        requireUpdated(rows);
        log.info("修改租户资料 tenantId={} 操作人={}", tenantId, StpUtil.getLoginIdAsLong());
        // 联系人与联系电话属于个人信息，摘要里只说改了哪些字段、不回显值（与账号那条同口径）
        List<String> changed = new ArrayList<>();
        if (!Objects.equals(before.getName(), dto.getName())) {
            changed.add("学校名 " + before.getName() + "→" + dto.getName());
        }
        if (!Objects.equals(before.getContact(), dto.getContact())) {
            changed.add("联系人");
        }
        if (!Objects.equals(before.getPhone(), dto.getPhone())) {
            changed.add("联系电话");
        }
        if (dto.getMaxStudents() != null && !Objects.equals(before.getMaxStudents(), dto.getMaxStudents())) {
            changed.add("学生配额 " + before.getMaxStudents() + "→" + dto.getMaxStudents());
        }
        if (dto.getMaxWorkers() != null && !Objects.equals(before.getMaxWorkers(), dto.getMaxWorkers())) {
            changed.add("维修工配额 " + before.getMaxWorkers() + "→" + dto.getMaxWorkers());
        }
        auditService.record(AuditAction.TENANT_UPDATE, AuditTarget.TENANT, tenantId, dto.getName(),
                "修改学校 " + before.getName() + "：" + (changed.isEmpty() ? "无字段变化" : String.join("、", changed)));
    }

    @Override
    public void changeStatus(long tenantId, int status) {
        Tenant tenant = requireTenant(tenantId);
        int rows = tenantMapper.update(null, Wrappers.<Tenant>lambdaUpdate()
                .eq(Tenant::getId, tenantId)
                .set(Tenant::getStatus, status));
        requireUpdated(rows);

        if (Integer.valueOf(STATUS_ENABLED).equals(status)) {
            log.info("启用租户 tenantId={} 操作人={}", tenantId, StpUtil.getLoginIdAsLong());
            auditService.record(AuditAction.TENANT_STATUS, AuditTarget.TENANT, tenantId, tenant.getName(),
                    "启用学校 " + tenant.getName());
            return;
        }
        // **先改库再踢人**，顺序不能反：反过来的话，踢人过程中新登录的用户读到的还是旧状态、
        // 能登进来，然后一直活到 token 过期。先改库则登录接口立刻开始拒绝新登录。
        //
        // 踢人要能列出这个学校的账号，而账号在**它自己的库**里（ADR-021）——所以这一段必须钉路由，
        // 否则查出来是空集，"停用"就只是个标签（这条是 TenantProvisionTest 抓出来的）。
        int online = TenantRoutingContext.call(routingKeyOf(tenant),
                () -> accountService.kickoutAllOfTenant(tenantId));
        log.info("停用租户 tenantId={} 踢下线在线数={} 操作人={}",
                tenantId, online, StpUtil.getLoginIdAsLong());
        // 踢下线的数量写进摘要：事后复盘"停用那天影响了多少人"就靠它
        auditService.record(AuditAction.TENANT_STATUS, AuditTarget.TENANT, tenantId, tenant.getName(),
                "停用学校 " + tenant.getName() + "（踢下线 " + online + " 个在线账号）");
    }

    @Override
    public List<TenantAdminVO> admins(long tenantId) {
        Tenant tenant = requireTenant(tenantId);
        // 管理员是这所学校自己的账号 → 去它的库里查（平台运营点的是"看它的管理员"）
        return TenantRoutingContext.call(routingKeyOf(tenant), () -> sysUserMapper.selectList(
                        Wrappers.<SysUser>lambdaQuery()
                                .eq(SysUser::getTenantId, tenantId)
                                .eq(SysUser::getUserType, UserType.ADMIN.getCode())
                                .orderByAsc(SysUser::getId))
                .stream()
                .map(TenantConverter::toAdminVO)
                .toList());
    }

    @Override
    public TenantAdminVO addAdmin(long tenantId, TenantAdminCreateDTO dto) {
        Tenant tenant = requireTenant(tenantId);
        // 账号写在学校自己的库里 → 切过去写（路由键换了，连接池也就换了，ADR-021）
        SysUser admin = TenantRoutingContext.call(routingKeyOf(tenant), () -> accountService.create(
                new AccountService.NewAccount(tenantId, dto.getUsername(), dto.getPassword(), dto.getRealName(),
                        dto.getPhone(), UserType.ADMIN.getCode(), UserType.ADMIN.getRoleCode(), true, "登录名")));
        log.info("新增租户管理员 tenantId={} userId={} username={} 操作人={}",
                tenantId, admin.getId(), admin.getUsername(), StpUtil.getLoginIdAsLong());
        // 审计落在**平台库**（tenant_id = 0，操作人是平台运营）：它不属于那所学校的台账，
        // 而且平台运营的动作在租户的审计页里本来也查不到（那一页按本租户过滤）
        TenantRoutingContext.run(TenantRoutingContext.CONTROL, () -> auditService.record(
                AuditAction.TENANT_ADMIN_ADD, AuditTarget.ACCOUNT, admin.getId(), admin.getUsername(),
                "为学校新增后勤管理员 " + admin.getUsername() + "（" + dto.getRealName() + "，首登需改密）"));
        return TenantConverter.toAdminVO(admin);
    }

    @Override
    public void resetAdminPassword(long tenantId, long userId, String rawPassword) {
        Tenant tenant = requireTenant(tenantId);
        String routingKey = routingKeyOf(tenant);
        // 校验、改口令、取用户名都要在学校自己的库里做（账号在那边）；
        // 口令本身绝不落库，日志与审计只记"重置了谁"（AGENTS §5.9）
        String username = TenantRoutingContext.call(routingKey, () -> {
            // require 校验"这个 id 是本租户的后勤管理员"：路径里的租户与用户必须对得上，
            // 否则平台能借 A 租户的路径改 B 租户的账号口令
            accountService.require(userId, tenantId, UserType.ADMIN.getCode(), ADMIN_LABEL);
            accountService.resetPassword(userId, tenantId, rawPassword, true);
            SysUser target = sysUserMapper.selectById(userId);
            return target == null ? String.valueOf(userId) : target.getUsername();
        });
        log.info("平台重置租户管理员口令 tenantId={} userId={} 操作人={}",
                tenantId, userId, StpUtil.getLoginIdAsLong());
        TenantRoutingContext.run(TenantRoutingContext.CONTROL, () -> auditService.record(
                AuditAction.TENANT_ADMIN_PASSWORD_RESET, AuditTarget.ACCOUNT, userId, username,
                "重置学校管理员 " + username + " 的口令（下次登录需改密）"));
    }

    /**
     * 取一个**真实存在的学校**，不存在（或就是平台自身）一律说"租户不存在"。
     *
     * <p>排除 {@code id = 0} 是这里最要紧的一行：没有它，平台就能停用自己、把自己的管理员
     * 口令重置掉——而它没有任何别的入口能把自己救回来。
     *
     * <p>读**平台库**：`tenant` 表只在那里（ADR-021）。钉住是必要的——这个方法被
     * "给某学校加管理员"这类已经切到租户库的流程调用过。
     */
    private Tenant requireTenant(long tenantId) {
        Tenant tenant = TenantRoutingContext.callControl(() -> tenantMapper.selectOne(Wrappers.<Tenant>lambdaQuery()
                .eq(Tenant::getId, tenantId)
                .ne(Tenant::getId, PLATFORM_TENANT_ID)));
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
