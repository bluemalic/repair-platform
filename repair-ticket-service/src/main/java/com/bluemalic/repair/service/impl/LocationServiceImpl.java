package com.bluemalic.repair.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bluemalic.repair.entity.Building;
import com.bluemalic.repair.entity.RepairCode;
import com.bluemalic.repair.mapper.BuildingMapper;
import com.bluemalic.repair.mapper.RepairCodeMapper;
import com.bluemalic.repair.service.CurrentTenantService;
import com.bluemalic.repair.service.LocationService;
import com.bluemalic.repair.vo.LocationVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 位置清单：**位置就是报修码，报修码就是位置**（ADR-004 + docs/01 §4.6）。
 *
 * <p>所以这里不做"另建一张位置表"：可报修的位置 = 本租户启用中、且**有码**的位置。
 * 好处是"手动选位置"与"扫码"共用同一套位置数据与同一套到场核验，不会出现
 * "选了位置但发不出码 / 到场没法验"的缝。
 *
 * <p>两条显式条件：`tenant_id`（本表不在数据权限拦截器名单里，AGENTS §5.6）与
 * `status = 1`（停用的码不让人选）。楼栋同样是"启用中"才出现——提交时服务端还会再校验一次，
 * 这里不出现只是让界面别给出一个注定失败的选项。
 */
@Service
@RequiredArgsConstructor
public class LocationServiceImpl implements LocationService {

    private final RepairCodeMapper repairCodeMapper;
    private final BuildingMapper buildingMapper;
    private final CurrentTenantService currentTenantService;

    @Override
    public List<LocationVO> list() {
        long tenantId = currentTenantService.requireTenantId();

        List<Building> buildings = buildingMapper.selectList(Wrappers.<Building>lambdaQuery()
                .eq(Building::getTenantId, tenantId)
                .eq(Building::getStatus, 1)
                .orderByAsc(Building::getSort)
                .orderByAsc(Building::getId));

        Map<Long, List<RepairCode>> byBuilding = repairCodeMapper.selectList(Wrappers.<RepairCode>lambdaQuery()
                        .eq(RepairCode::getTenantId, tenantId)
                        .eq(RepairCode::getStatus, 1)
                        // 先宿舍房间、后公共区域，同类按名称排：界面上的顺序要稳定，别每次刷新都变
                        .orderByAsc(RepairCode::getLocationType)
                        .orderByAsc(RepairCode::getRoom))
                .stream()
                .collect(Collectors.groupingBy(RepairCode::getBuildingId));

        return buildings.stream()
                .filter(building -> byBuilding.containsKey(building.getId()))
                .map(building -> {
                    LocationVO vo = new LocationVO();
                    vo.setBuildingId(building.getId());
                    vo.setBuildingName(building.getName());
                    vo.setLocations(byBuilding.get(building.getId()).stream().map(code -> {
                        LocationVO.LocationItem item = new LocationVO.LocationItem();
                        item.setRoom(code.getRoom());
                        item.setLocationType(code.getLocationType());
                        return item;
                    }).toList());
                    return vo;
                })
                .toList();
    }
}
