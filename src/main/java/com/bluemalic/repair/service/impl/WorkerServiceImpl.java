package com.bluemalic.repair.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bluemalic.repair.common.BizException;
import com.bluemalic.repair.common.ErrorCode;
import com.bluemalic.repair.common.UserType;
import com.bluemalic.repair.converter.WorkerConverter;
import com.bluemalic.repair.dto.WorkerBuildingsDTO;
import com.bluemalic.repair.dto.WorkerCreateDTO;
import com.bluemalic.repair.dto.WorkerUpdateDTO;
import com.bluemalic.repair.entity.Building;
import com.bluemalic.repair.entity.SysUser;
import com.bluemalic.repair.entity.WorkerBuilding;
import com.bluemalic.repair.mapper.BuildingMapper;
import com.bluemalic.repair.mapper.SysUserMapper;
import com.bluemalic.repair.mapper.WorkerBuildingMapper;
import com.bluemalic.repair.service.AccountService;
import com.bluemalic.repair.service.CurrentTenantService;
import com.bluemalic.repair.service.WorkerService;
import com.bluemalic.repair.vo.PageResult;
import com.bluemalic.repair.vo.WorkerVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * 维修工管理。
 *
 * <p>账号本身的操作（建号、改资料、启停、重置口令）在
 * {@link AccountService} 里，维修工与学生共用同一套；这里只做维修工特有的部分：
 * 列表（带负责楼栋）与「设置负责楼栋」。
 *
 * <p><b>租户条件必须显式写。</b>数据权限拦截器只覆盖 `ticket`（租户 + 角色两层）和 `notification`
 * （receiver_id）两张表，`sys_user` / `worker_building` 这些**不在它的范围内**——这里的每一条查询
 * 都自己带 `tenant_id`，漏一处就是跨租户读写。反过来也成立：不写就没人替你写。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkerServiceImpl implements WorkerService {

    /** 对外统一的"找不到"文案；也用于只记 ID 的日志。 */
    private static final String ACCOUNT_LABEL = "维修工";

    private final SysUserMapper sysUserMapper;
    private final WorkerBuildingMapper workerBuildingMapper;
    private final BuildingMapper buildingMapper;
    private final AccountService accountService;
    private final CurrentTenantService currentTenantService;

    @Override
    public PageResult<WorkerVO> page(long pageNum, long pageSize, Integer status, String keyword) {
        long tenantId = currentTenantService.requireTenantId();

        LambdaQueryWrapper<SysUser> query = Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getTenantId, tenantId)
                // 按 userType 而不是"有没有 WORKER 角色"来筛：sys_user 上有 idx_tenant_type，
                // 而角色关联是另一张表，为一次列表查询去 join 它不值得
                .eq(SysUser::getUserType, UserType.WORKER.getCode())
                .eq(status != null, SysUser::getStatus, status);
        if (StringUtils.hasText(keyword)) {
            // 工号或姓名命中即可；like 的值由 MyBatis 参数化，不存在拼接注入
            query.and(w -> w.like(SysUser::getUsername, keyword).or().like(SysUser::getRealName, keyword));
        }

        Page<SysUser> page = sysUserMapper.selectPage(
                new Page<>(clamp(pageNum), clamp(pageSize)),
                query.orderByAsc(SysUser::getUsername));

        List<Long> workerIds = page.getRecords().stream().map(SysUser::getId).toList();
        Map<Long, List<Long>> buildingIdsByWorker = buildingIdsByWorker(workerIds);
        Map<Long, String> buildingNames = buildingNames(buildingIdsByWorker.values().stream()
                .flatMap(List::stream).distinct().toList());

        Page<WorkerVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(page.getRecords().stream()
                .map(worker -> {
                    List<Long> ids = buildingIdsByWorker.getOrDefault(worker.getId(), List.of());
                    return WorkerConverter.toVO(worker, ids,
                            ids.stream().map(buildingNames::get).filter(Objects::nonNull).toList());
                })
                .toList());
        return PageResult.of(voPage);
    }

    @Override
    @Transactional
    public WorkerVO create(WorkerCreateDTO dto) {
        long tenantId = currentTenantService.requireTenantId();

        // 用户类型与角色由服务端写死（见 UserType），接口造不出后勤管理员
        SysUser worker = accountService.create(new AccountService.NewAccount(
                tenantId, dto.getUsername(), dto.getPassword(), dto.getRealName(), dto.getPhone(),
                UserType.WORKER.getCode(), UserType.WORKER.getRoleCode(),
                // 维修工不强制首登改密：初始口令是管理员**当面告知**的，当场就能改。
                // 学生那边不一样——口令只能统一发放，而学号在班里是公开的，所以必须强制（见 StudentServiceImpl）
                false,
                "工号"));

        log.info("新增维修工 workerId={} username={} operator={}",
                worker.getId(), worker.getUsername(), StpUtil.getLoginIdAsLong());
        return WorkerConverter.toVO(worker, List.of(), List.of());
    }

    @Override
    @Transactional
    public void update(long id, WorkerUpdateDTO dto) {
        long tenantId = currentTenantService.requireTenantId();
        accountService.require(id, tenantId, UserType.WORKER.getCode(), ACCOUNT_LABEL);

        accountService.updateProfile(id, tenantId, dto.getRealName(), dto.getPhone());
        // 停用时的踢下线在 changeStatus 里，那条不变量属于账号层
        accountService.changeStatus(id, tenantId, dto.getStatus(), ACCOUNT_LABEL);

        if (dto.getPassword() != null) {
            // 管理员帮师傅重置口令，与新增时同一套语义：不强制首登改密
            accountService.resetPassword(id, tenantId, dto.getPassword(), false);
            log.info("管理员重置维修工口令 workerId={} operator={}", id, StpUtil.getLoginIdAsLong());
        } else {
            log.info("修改维修工信息 workerId={} operator={}", id, StpUtil.getLoginIdAsLong());
        }
    }

    /**
     * 设置负责楼栋（全量替换）。
     *
     * <p>**校验必须发生在删除之前**：如果先删再校验，一个跨租户 / 不存在的楼栋 ID 就会让这次请求
     * 报错返回，而师傅原有的授权**已经被清空了**——调用方看到的是"失败"，实际数据已经被改坏。
     * 事务能兜住异常，但兜不住这种"先破坏后校验"的顺序。
     */
    @Override
    @Transactional
    public void setBuildings(long id, WorkerBuildingsDTO dto) {
        long tenantId = currentTenantService.requireTenantId();
        accountService.require(id, tenantId, UserType.WORKER.getCode(), ACCOUNT_LABEL);

        List<Long> buildingIds = dto.getBuildingIds().stream()
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        if (!buildingIds.isEmpty()) {
            List<Building> buildings = buildingMapper.selectList(Wrappers.<Building>lambdaQuery()
                    .in(Building::getId, buildingIds)
                    .eq(Building::getTenantId, tenantId));
            if (buildings.size() != buildingIds.size()) {
                // 不区分"不存在"与"是别家租户的"，都当作不存在
                throw new BizException(ErrorCode.PARAM_INVALID, "楼栋不存在或不属于本租户");
            }
        }

        // worker_building 没有 deleted 字段（纯关联表），这里是真删除——解除负责关系就是删行
        workerBuildingMapper.delete(Wrappers.<WorkerBuilding>lambdaQuery()
                .eq(WorkerBuilding::getWorkerId, id)
                .eq(WorkerBuilding::getTenantId, tenantId));
        for (Long buildingId : buildingIds) {
            WorkerBuilding link = new WorkerBuilding();
            link.setTenantId(tenantId);
            link.setWorkerId(id);
            link.setBuildingId(buildingId);
            workerBuildingMapper.insert(link);
        }

        // 这是数据权限的物理依据，改动会影响他能看到的全部工单，属于必须留痕的业务节点
        log.info("设置负责楼栋 workerId={} buildingIds={} operator={}",
                id, buildingIds, StpUtil.getLoginIdAsLong());
    }

    /** worker_id → 负责的楼栋 ID 列表（一次查完，避免逐行回库）。 */
    private Map<Long, List<Long>> buildingIdsByWorker(List<Long> workerIds) {
        if (workerIds.isEmpty()) {
            return Map.of();
        }
        return workerBuildingMapper.selectList(Wrappers.<WorkerBuilding>lambdaQuery()
                        .in(WorkerBuilding::getWorkerId, workerIds))
                .stream()
                .collect(Collectors.groupingBy(WorkerBuilding::getWorkerId,
                        Collectors.mapping(WorkerBuilding::getBuildingId, Collectors.toList())));
    }

    /** 楼栋 ID → 名称。 */
    private Map<Long, String> buildingNames(List<Long> buildingIds) {
        if (buildingIds.isEmpty()) {
            return Map.of();
        }
        return buildingMapper.selectByIds(buildingIds).stream()
                .collect(Collectors.toMap(Building::getId, Building::getName));
    }

    private long clamp(long value) {
        if (value < 1) {
            return 1;
        }
        return Math.min(value, 100);
    }
}
