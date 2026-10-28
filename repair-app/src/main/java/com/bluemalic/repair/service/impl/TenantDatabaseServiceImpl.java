package com.bluemalic.repair.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bluemalic.repair.common.AuditAction;
import com.bluemalic.repair.common.AuditTarget;
import com.bluemalic.repair.common.BizException;
import com.bluemalic.repair.common.ErrorCode;
import com.bluemalic.repair.entity.Tenant;
import com.bluemalic.repair.mapper.TenantMapper;
import com.bluemalic.repair.service.AuditService;
import com.bluemalic.repair.service.TenantDatabaseService;
import com.bluemalic.repair.tenancy.TenantDataMigrator;
import com.bluemalic.repair.tenancy.TenantDatabaseProvisioner;
import com.bluemalic.repair.tenancy.TenantRoutingContext;
import com.bluemalic.repair.tenancy.TenantSchemaCatalog;
import com.bluemalic.repair.vo.TenantDatabaseTableVO;
import com.bluemalic.repair.vo.TenantDatabaseVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 存量租户搬迁的实现（阶段 6 第 2 步 · ADR-021）。
 *
 * <p>这个类只做**编排与判断**（校验结果算不算通过、什么时候翻路由、审计与错误文案），
 * 真正的 SQL 在 {@link TenantDataMigrator}（跨库的搬运与比对）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TenantDatabaseServiceImpl implements TenantDatabaseService {

    /** 平台自身在 `tenant` 表里的 id——它没有自己的库，也不该被搬 */
    private static final long PLATFORM_TENANT_ID = 0L;

    private static final String MODE_SHARED = "SHARED";
    private static final String MODE_DEDICATED = "DEDICATED";

    /** 校验不通过时，最多在错误文案里列几张表（其余的看日志与 status 接口） */
    private static final int MAX_REPORTED_TABLES = 3;

    private final TenantMapper tenantMapper;
    private final TenantDataMigrator migrator;
    private final TenantDatabaseProvisioner databaseProvisioner;
    private final TenantSchemaCatalog schemaCatalog;
    private final AuditService auditService;

    @Override
    public TenantDatabaseVO status(long tenantId) {
        Tenant tenant = requireTenant(tenantId);
        return report(tenant, true);
    }

    @Override
    public TenantDatabaseVO copy(long tenantId) {
        Tenant tenant = requireTenant(tenantId);
        requireNotMigrated(tenant);

        String schema = databaseProvisioner.schemaFor(tenant.getCode());
        // 建库 + 跑租户库迁移（幂等）：目标库的结构与平台库由 TenantSchemaParityTest 逐列保证
        databaseProvisioner.createAndMigrate(schema);
        var copied = migrator.copy(tenant.getId(), schema);
        long rows = copied.values().stream().mapToLong(Long::longValue).sum();
        log.info("租户数据搬运完成 tenantId={} code={} schema={} 表数={} 总行数={}",
                tenant.getId(), tenant.getCode(), schema, copied.size(), rows);

        TenantDatabaseVO vo = report(tenant, true);
        auditService.record(AuditAction.TENANT_DATABASE_COPY, AuditTarget.TENANT, tenant.getId(), tenant.getName(),
                "把学校 " + tenant.getName() + " 的数据搬进它自己的库 " + schema + "："
                        + copied.size() + " 张表共 " + rows + " 行，校验"
                        + (Boolean.TRUE.equals(vo.getConsistent()) ? "一致" : "**不一致**（先别切换，看 status 明细）"));
        return vo;
    }

    @Override
    public TenantDatabaseVO switchToDedicated(long tenantId) {
        Tenant tenant = requireTenant(tenantId);
        requireNotMigrated(tenant);

        String schema = databaseProvisioner.schemaFor(tenant.getCode());
        if (!migrator.databaseExists(schema)) {
            // 状态不对：先搬家。用 10001 而不是 10005——这是"操作顺序不对"，不是系统故障
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "这所学校的数据还没搬过（库 " + schema + " 不存在），请先执行搬家");
        }

        // 翻转期间把该校的请求挡在门外：窗口从"几秒"缩到"在途的那几个请求"。
        // 挡的是**该校的**请求，平台运营自己（tenantId = 0）不受影响——否则这一步就没法执行了
        migrator.markSwitching(tenantId);
        try {
            List<TenantDataMigrator.TableCheck> checks = migrator.verify(tenantId, schema);
            List<TenantDataMigrator.TableCheck> bad = checks.stream()
                    .filter(check -> !check.consistent())
                    .toList();
            if (!bad.isEmpty()) {
                throw new BizException(ErrorCode.PARAM_INVALID,
                        "不切换：源库与目标库不一致（" + describe(bad) + "）。"
                                + "通常是搬完之后源库又被写过——请重新执行一次搬家，再切换");
            }

            // 唯一的写入点：把库名写进 tenant.db_schema。带上 db_schema IS NULL 是为了并发下不会重复切
            int rows = TenantRoutingContext.callControl(() -> tenantMapper.update(null,
                    Wrappers.<Tenant>lambdaUpdate()
                            .eq(Tenant::getId, tenantId)
                            .isNull(Tenant::getDbSchema)
                            .set(Tenant::getDbSchema, schema)));
            if (rows == 0) {
                throw new BizException(ErrorCode.PARAM_INVALID, "这所学校刚被切换过，请刷新后重试");
            }
            // 目录缓存按租户存着"去哪个库"：不作废的话，切换后的请求还会被送到平台库
            schemaCatalog.refresh(tenantId);
            // 让它后面的报告直接是"已在自己库里"的形态（内存里的实体同步一下，别再查一次库）
            tenant.setDbSchema(schema);
            // AI 只读账号的授权要把新库纳进来（这一步之后它才在"已搬家的租户"名单里）
            databaseProvisioner.ensureAiReadOnlyOnAllDatabases();
        } finally {
            migrator.unmarkSwitching(tenantId);
        }

        log.info("租户已切换到独立库 tenantId={} code={} schema={} 操作人={}",
                tenant.getId(), tenant.getCode(), schema, cn.dev33.satoken.stp.StpUtil.getLoginIdAsLong());
        auditService.record(AuditAction.TENANT_DATABASE_SWITCH, AuditTarget.TENANT, tenant.getId(), tenant.getName(),
                "学校 " + tenant.getName() + " 切换到它自己的库 " + schema + "（源库那份保留，确认后再归档）");        return report(tenant, false);
    }

    // ==================== 报告 ====================

    /** @param check 是否顺带做逐表校验（status/copy 要，切换之后不要——见下） */
    private TenantDatabaseVO report(Tenant tenant, boolean check) {
        TenantDatabaseVO vo = new TenantDatabaseVO();
        vo.setTenantId(tenant.getId());
        vo.setCode(tenant.getCode());
        vo.setDbSchema(tenant.getDbSchema());
        vo.setCheckedAt(LocalDateTime.now());
        if (tenant.getDbSchema() != null) {
            // 已经在自己的库里：**不做一致性比对**。源库那份是"搬走那一刻"的快照，
            // 之后两边各自变化（新库在接写入、源库冻结），比对结果没有意义
            vo.setMode(MODE_DEDICATED);
            vo.setTargetReady(true);
            vo.setTables(List.of());
            return vo;
        }
        vo.setMode(MODE_SHARED);
        String schema = databaseProvisioner.schemaFor(tenant.getCode());
        boolean targetReady = migrator.databaseExists(schema);
        vo.setTargetReady(targetReady);
        if (!targetReady || !check) {
            vo.setTables(List.of());
            return vo;
        }
        List<TenantDataMigrator.TableCheck> checks = migrator.verify(tenant.getId(), schema);
        vo.setTables(checks.stream().map(TenantDatabaseServiceImpl::toVO).toList());
        vo.setConsistent(checks.stream().allMatch(TenantDataMigrator.TableCheck::consistent));
        vo.setSourceRows(checks.stream().mapToLong(TenantDataMigrator.TableCheck::sourceRows).sum());
        vo.setTargetRows(checks.stream().mapToLong(TenantDataMigrator.TableCheck::targetRows).sum());
        return vo;
    }

    private static TenantDatabaseTableVO toVO(TenantDataMigrator.TableCheck check) {
        TenantDatabaseTableVO vo = new TenantDatabaseTableVO();
        vo.setTable(check.table());
        vo.setStructureMatches(check.structureMatches());
        vo.setConsistent(check.consistent());
        if (check.sourceRows() >= 0) {
            vo.setSourceRows(check.sourceRows());
            vo.setTargetRows(check.targetRows());
        }
        if (!check.consistent()) {
            vo.setDetail(check.structureMatches()
                    ? "行数或内容不一致：平台库 " + check.sourceRows() + " 行 / 租户库 " + check.targetRows() + " 行"
                    : "两侧表结构不一致（列名/顺序/类型）——搬迁脚本与建表脚本漂开了，请先核对 "
                            + "db/migration 与 db/tenant 两份脚本");
        }
        return vo;
    }

    private String describe(List<TenantDataMigrator.TableCheck> bad) {
        return bad.stream().limit(MAX_REPORTED_TABLES)
                .map(check -> check.structureMatches()
                        ? check.table() + "（平台 " + check.sourceRows() + " 行 / 租户库 " + check.targetRows() + " 行）"
                        : check.table() + "（结构不一致）")
                .collect(Collectors.joining("、")) + (bad.size() > MAX_REPORTED_TABLES ? " 等 " + bad.size() + " 张表" : "");
    }

    // ==================== 前置检查 ====================

    /** 取一个真实存在的学校（排除平台自身），读**平台库**（`tenant` 表只在那里，ADR-021）。 */
    private Tenant requireTenant(long tenantId) {
        Tenant tenant = TenantRoutingContext.callControl(() -> tenantMapper.selectOne(Wrappers.<Tenant>lambdaQuery()
                .eq(Tenant::getId, tenantId)
                .ne(Tenant::getId, PLATFORM_TENANT_ID)));
        if (tenant == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "租户不存在");
        }
        return tenant;
    }

    private void requireNotMigrated(Tenant tenant) {
        if (tenant.getDbSchema() != null) {
            throw new BizException(ErrorCode.PARAM_INVALID,
                    "学校 " + tenant.getName() + " 的数据已经在它自己的库里（" + tenant.getDbSchema()
                            + "），不需要再搬（把 db_schema 改回空才能重做，见 docs/05 §5.4）");
        }
    }
}
