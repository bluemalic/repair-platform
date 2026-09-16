package com.bluemalic.repair.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bluemalic.repair.common.BizException;
import com.bluemalic.repair.common.ErrorCode;
import com.bluemalic.repair.converter.BuildingConverter;
import com.bluemalic.repair.dto.BuildingCreateDTO;
import com.bluemalic.repair.dto.BuildingUpdateDTO;
import com.bluemalic.repair.entity.Building;
import com.bluemalic.repair.entity.RepairCode;
import com.bluemalic.repair.entity.Ticket;
import com.bluemalic.repair.entity.WorkerBuilding;
import com.bluemalic.repair.mapper.BuildingMapper;
import com.bluemalic.repair.mapper.RepairCodeMapper;
import com.bluemalic.repair.mapper.TicketMapper;
import com.bluemalic.repair.mapper.WorkerBuildingMapper;
import com.bluemalic.repair.service.BuildingService;
import com.bluemalic.repair.service.CurrentTenantService;
import com.bluemalic.repair.vo.BuildingVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 楼栋管理。
 *
 * <p>租户条件显式写在每条查询上：`building` 不在数据权限拦截器的范围内（它只管 ticket / notification），
 * 见 AGENTS §5.6。
 *
 * <p><b>删除为什么有前置校验</b>：这三张表（ticket / worker_building / repair_code）都会引用楼栋，
 * 而 MyBatis-Plus 的全局逻辑删除会把软删行从**所有**查询里滤掉——`TicketServiceImpl.buildingNames`
 * 用的就是被过滤的 `selectByIds`。所以一旦删掉一个被引用的楼栋，会出现"引用还在、名字查不到"：
 * 历史工单的楼栋名变成空白、学生扫码报修拿不到楼栋名、师傅的可见范围里挂着一个不存在的楼栋。
 * 结果是：**用过的楼栋只能停用（status=0），删除只留给"建错了、从没被用过"的那种**。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BuildingServiceImpl implements BuildingService {

    private static final int STATUS_ENABLED = 1;

    private final BuildingMapper buildingMapper;
    private final TicketMapper ticketMapper;
    private final RepairCodeMapper repairCodeMapper;
    private final WorkerBuildingMapper workerBuildingMapper;
    private final CurrentTenantService currentTenantService;

    @Override
    public List<BuildingVO> list(Integer status) {
        long tenantId = currentTenantService.requireTenantId();
        return buildingMapper.selectList(Wrappers.<Building>lambdaQuery()
                        .eq(Building::getTenantId, tenantId)
                        .eq(status != null, Building::getStatus, status)
                        .orderByAsc(Building::getSort)
                        .orderByAsc(Building::getId))
                .stream()
                .map(BuildingConverter::toVO)
                .toList();
    }

    @Override
    @Transactional
    public BuildingVO create(BuildingCreateDTO dto) {
        long tenantId = currentTenantService.requireTenantId();
        requireNameNotUsed(tenantId, dto.getName(), null);

        Building building = new Building();
        building.setTenantId(tenantId);
        building.setName(dto.getName());
        building.setArea(dto.getArea());
        building.setSort(dto.getSort() == null ? 0 : dto.getSort());
        building.setStatus(STATUS_ENABLED);
        buildingMapper.insert(building);

        log.info("新增楼栋 buildingId={} name={} operator={}",
                building.getId(), building.getName(), StpUtil.getLoginIdAsLong());
        return BuildingConverter.toVO(building);
    }

    @Override
    @Transactional
    public void update(long id, BuildingUpdateDTO dto) {
        long tenantId = currentTenantService.requireTenantId();
        requireBuilding(id, tenantId);
        requireNameNotUsed(tenantId, dto.getName(), id);

        int rows = buildingMapper.update(null, Wrappers.<Building>lambdaUpdate()
                .eq(Building::getId, id)
                .eq(Building::getTenantId, tenantId)
                .set(Building::getName, dto.getName())
                .set(Building::getArea, dto.getArea())
                .set(Building::getSort, dto.getSort())
                .set(Building::getStatus, dto.getStatus()));
        if (rows == 0) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "楼栋不存在");
        }
        log.info("修改楼栋 buildingId={} name={} status={} operator={}",
                id, dto.getName(), dto.getStatus(), StpUtil.getLoginIdAsLong());
    }

    @Override
    @Transactional
    public void delete(long id) {
        long tenantId = currentTenantService.requireTenantId();
        Building building = requireBuilding(id, tenantId);

        // 先查引用、再删。顺序反了的话，报错返回时数据已经被删掉了（和"设置负责楼栋"同一个坑）
        if (countTickets(id) > 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "该楼栋下已有工单，不能删除；下线请改为停用");
        }
        if (countWorkerAssignments(id, tenantId) > 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "还有维修工负责该楼栋，请先在维修工管理里解除");
        }
        if (countRepairCodes(id) > 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "该楼栋下还有报修码，请先处理报修码");
        }

        // 逻辑删除（MP 的全局配置把 deleteById 变成 UPDATE ... SET deleted = 1），行还在，便于事后查证
        buildingMapper.deleteById(id);
        log.info("删除未被引用的楼栋 buildingId={} name={} operator={}",
                id, building.getName(), StpUtil.getLoginIdAsLong());
    }

    private long countTickets(long buildingId) {
        Long count = ticketMapper.selectCount(Wrappers.<Ticket>lambdaQuery()
                .eq(Ticket::getBuildingId, buildingId));
        return count == null ? 0 : count;
    }

    private long countRepairCodes(long buildingId) {
        Long count = repairCodeMapper.selectCount(Wrappers.<RepairCode>lambdaQuery()
                .eq(RepairCode::getBuildingId, buildingId));
        return count == null ? 0 : count;
    }

    private long countWorkerAssignments(long buildingId, long tenantId) {
        Long count = workerBuildingMapper.selectCount(Wrappers.<WorkerBuilding>lambdaQuery()
                .eq(WorkerBuilding::getBuildingId, buildingId)
                .eq(WorkerBuilding::getTenantId, tenantId));
        return count == null ? 0 : count;
    }

    /** 取本租户的楼栋，不存在一律说"不存在"（不区分"是别家租户的"）。 */
    private Building requireBuilding(long id, long tenantId) {
        Building building = buildingMapper.selectOne(Wrappers.<Building>lambdaQuery()
                .eq(Building::getId, id)
                .eq(Building::getTenantId, tenantId));
        if (building == null) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "楼栋不存在");
        }
        return building;
    }

    /**
     * 名称查重。**数据库没有唯一索引兜底**：字典表加 `uk_tenant_name` 会和逻辑删除打架
     * （软删后同一名称就再也建不回来），所以只能放在应用层。
     *
     * <p>代价如实说明：两个管理员同时提交同名楼栋，理论上都能通过这次查询（各看各的）。
     * 这里接受这个窗口——后果只是列表里多一个同名项，管理员自己删掉即可，不值得为它引入
     * 分布式锁或改表结构。
     */
    private void requireNameNotUsed(long tenantId, String name, Long excludeId) {
        Long count = buildingMapper.selectCount(Wrappers.<Building>lambdaQuery()
                .eq(Building::getTenantId, tenantId)
                .eq(Building::getName, name)
                .ne(excludeId != null, Building::getId, excludeId));
        if (count != null && count > 0) {
            throw new BizException(ErrorCode.PARAM_INVALID, "楼栋名称已存在");
        }
    }
}
