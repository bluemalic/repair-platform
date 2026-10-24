package com.bluemalic.repair.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bluemalic.repair.common.BizException;
import com.bluemalic.repair.common.ErrorCode;
import com.bluemalic.repair.common.UserType;
import com.bluemalic.repair.entity.SysUser;
import com.bluemalic.repair.entity.WorkerBuilding;
import com.bluemalic.repair.mapper.SysUserMapper;
import com.bluemalic.repair.mapper.WorkerBuildingMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

/**
 * 派单/转派的目标校验与留痕：师傅必须是本租户、启用中；跨楼栋派单放行但要留下说明。
 *
 * <p>跨租户的师傅与“不存在的师傅”返回同一个错误——不告诉调用方“这个师傅是别家的”。
 */
@Slf4j
@Component
@RequiredArgsConstructor
class TicketAssignmentPolicy {

    private final SysUserMapper sysUserMapper;
    private final WorkerBuildingMapper workerBuildingMapper;

    SysUser requireEnabledWorker(Long workerId, Long tenantId) {
        SysUser worker = sysUserMapper.selectById(workerId);
        if (worker == null || !Integer.valueOf(UserType.WORKER.getCode()).equals(worker.getUserType())
                || !Integer.valueOf(1).equals(worker.getStatus())
                || !tenantId.equals(worker.getTenantId())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "维修工不存在或已停用");
        }
        return worker;
    }

    /**
     * 跨楼栋强制派单的留痕文案；师傅负责这栋楼时返回 {@code null}（正常派单不写多余备注）。
     *
     * <p>它进 `ticket_log.remark`，所以在管理端的工单时间线上看得见“这一单是被谁强行跨楼栋派下来的”。
     */
    String crossBuildingTrace(Long tenantId, long buildingId, long workerId) {
        boolean covered = workerBuildingMapper.selectCount(Wrappers.<WorkerBuilding>lambdaQuery()
                .eq(WorkerBuilding::getTenantId, tenantId)
                .eq(WorkerBuilding::getWorkerId, workerId)
                .eq(WorkerBuilding::getBuildingId, buildingId)) > 0;
        if (covered) {
            return null;
        }
        log.warn("跨楼栋强制派单 buildingId={} workerId={}（该师傅不负责这栋楼）", buildingId, workerId);
        return "跨楼栋强制派单（该师傅不负责本单楼栋）";
    }
}
