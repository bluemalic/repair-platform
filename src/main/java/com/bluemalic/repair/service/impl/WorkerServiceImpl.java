package com.bluemalic.repair.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bluemalic.repair.common.BizException;
import com.bluemalic.repair.common.ErrorCode;
import com.bluemalic.repair.converter.WorkerConverter;
import com.bluemalic.repair.dto.WorkerBuildingsDTO;
import com.bluemalic.repair.dto.WorkerCreateDTO;
import com.bluemalic.repair.dto.WorkerUpdateDTO;
import com.bluemalic.repair.entity.Building;
import com.bluemalic.repair.entity.SysRole;
import com.bluemalic.repair.entity.SysUser;
import com.bluemalic.repair.entity.SysUserRole;
import com.bluemalic.repair.entity.WorkerBuilding;
import com.bluemalic.repair.mapper.BuildingMapper;
import com.bluemalic.repair.mapper.SysRoleMapper;
import com.bluemalic.repair.mapper.SysUserMapper;
import com.bluemalic.repair.mapper.SysUserRoleMapper;
import com.bluemalic.repair.mapper.WorkerBuildingMapper;
import com.bluemalic.repair.service.CurrentTenantService;
import com.bluemalic.repair.service.WorkerService;
import com.bluemalic.repair.vo.PageResult;
import com.bluemalic.repair.vo.WorkerVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.security.crypto.password.PasswordEncoder;
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
 * <p><b>租户条件必须显式写。</b> 数据权限拦截器只覆盖 `ticket`（租户 + 角色两层）和 `notification`
 * （receiver_id）两张表，`sys_user` / `worker_building` 这些**不在它的范围内**——这里的每一条查询
 * 都自己带 `tenant_id`，漏一处就是跨租户读写。反过来也成立：不写就没人替你写。
 *
 * <p>另一个容易忽略的点：**停用必须同时让既有登录态失效**。登录态在 Redis，不随 `sys_user.status`
 * 变化，只写库的话他手上那个 7 天有效的 token 照样能接单、能上报完工。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkerServiceImpl implements WorkerService {

    /** 维修工在 {@code sys_user.user_type} 里的取值；写死在这里，使"新增维修工"无法造出后勤账号。 */
    private static final int USER_TYPE_WORKER = 2;

    /** 角色码，与 {@code sys_role.code} 的种子数据一致（StpInterfaceImpl 也按这个码认角色）。 */
    private static final String ROLE_CODE_WORKER = "WORKER";

    private static final int STATUS_ENABLED = 1;

    private final SysUserMapper sysUserMapper;
    private final SysUserRoleMapper sysUserRoleMapper;
    private final SysRoleMapper sysRoleMapper;
    private final WorkerBuildingMapper workerBuildingMapper;
    private final BuildingMapper buildingMapper;
    private final PasswordEncoder passwordEncoder;
    private final CurrentTenantService currentTenantService;

    @Override
    public PageResult<WorkerVO> page(long pageNum, long pageSize, Integer status, String keyword) {
        long tenantId = currentTenantService.requireTenantId();

        LambdaQueryWrapper<SysUser> query = Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getTenantId, tenantId)
                // 按 userType 而不是"有没有 WORKER 角色"来筛：sys_user 上有 idx_tenant_type，
                // 而角色关联是另一张表，为一次列表查询去 join 它不值得
                .eq(SysUser::getUserType, USER_TYPE_WORKER)
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

        SysUser worker = new SysUser();
        worker.setTenantId(tenantId);
        worker.setUsername(dto.getUsername());
        // 密码只在这一次写入里出现，绝不回读、不打日志
        worker.setPassword(passwordEncoder.encode(dto.getPassword()));
        worker.setRealName(dto.getRealName());
        worker.setPhone(dto.getPhone());
        worker.setUserType(USER_TYPE_WORKER);
        worker.setStatus(STATUS_ENABLED);
        try {
            sysUserMapper.insert(worker);
        } catch (DuplicateKeyException e) {
            // uk_tenant_username 兜底：并发下两个管理员建同一个工号，到这里变成明确的业务错误；
            // 对外不说"这是谁的账号"，只说工号被占了
            throw new BizException(ErrorCode.PARAM_INVALID, "工号已存在");
        }

        // 角色关联必须写：少了它，新师傅能登录但拿不到任何权限码，每个接口都 403
        SysUserRole link = new SysUserRole();
        link.setUserId(worker.getId());
        link.setRoleId(workerRoleId());
        sysUserRoleMapper.insert(link);

        log.info("新增维修工 workerId={} username={} operator={}",
                worker.getId(), worker.getUsername(), StpUtil.getLoginIdAsLong());
        return WorkerConverter.toVO(worker, List.of(), List.of());
    }

    @Override
    @Transactional
    public void update(long id, WorkerUpdateDTO dto) {
        long tenantId = currentTenantService.requireTenantId();
        requireWorker(id, tenantId);

        String encodedPassword = dto.getPassword() == null ? null : passwordEncoder.encode(dto.getPassword());
        int rows = sysUserMapper.update(null, Wrappers.<SysUser>lambdaUpdate()
                .eq(SysUser::getId, id)
                .eq(SysUser::getTenantId, tenantId)
                .set(SysUser::getRealName, dto.getRealName())
                .set(SysUser::getPhone, dto.getPhone())
                .set(SysUser::getStatus, dto.getStatus())
                .set(encodedPassword != null, SysUser::getPassword, encodedPassword));
        if (rows == 0) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "维修工不存在");
        }

        if (!Integer.valueOf(STATUS_ENABLED).equals(dto.getStatus())) {
            // 停用只写库是不够的：登录态在 Redis，不受 status 影响，他手上的 token 还能用满 7 天。
            // isLogin 先判断是必要的——从没登录过的账号直接 kickout 会抛异常，
            // 而"新建完立刻停用"是很常见的操作顺序。
            if (StpUtil.isLogin(id)) {
                StpUtil.kickout(id);
                log.info("停用维修工并踢下线 workerId={} operator={}", id, StpUtil.getLoginIdAsLong());
            } else {
                log.info("停用维修工（该账号当前无登录态）workerId={} operator={}",
                        id, StpUtil.getLoginIdAsLong());
            }
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
        requireWorker(id, tenantId);

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

    /** 取本租户的维修工；不是维修工 / 不在本租户 / 不存在，对外一律说"不存在"。 */
    private SysUser requireWorker(long id, long tenantId) {
        SysUser worker = sysUserMapper.selectOne(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getId, id)
                .eq(SysUser::getTenantId, tenantId));
        if (worker == null || !Integer.valueOf(USER_TYPE_WORKER).equals(worker.getUserType())) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "维修工不存在");
        }
        return worker;
    }

    /**
     * 按角色码取角色 ID，不写死数字：写死的话，角色 ID 一旦调整就会静默造出"能登录但每个接口都 403"
     * 的账号，那种问题排查起来非常费时间。
     *
     * <p>当前角色是全局的（种子数据 `sys_role.tenant_id = 0`），所以只按码查；将来若变成各租户自带角色，
     * 这里就是唯一需要改的地方。
     */
    private long workerRoleId() {
        return sysRoleMapper.selectList(Wrappers.<SysRole>lambdaQuery()
                        .eq(SysRole::getCode, ROLE_CODE_WORKER))
                .stream()
                .findFirst()
                .orElseThrow(() -> new BizException(ErrorCode.SYSTEM_ERROR, "角色数据缺失：WORKER"))
                .getId();
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
