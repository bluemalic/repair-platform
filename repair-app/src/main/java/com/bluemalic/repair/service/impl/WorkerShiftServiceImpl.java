package com.bluemalic.repair.service.impl;

import cn.dev33.satoken.stp.StpUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bluemalic.repair.common.AuditAction;
import com.bluemalic.repair.common.AuditTarget;
import com.bluemalic.repair.common.BizException;
import com.bluemalic.repair.common.ErrorCode;
import com.bluemalic.repair.common.Texts;
import com.bluemalic.repair.common.UserType;
import com.bluemalic.repair.converter.WorkerShiftConverter;
import com.bluemalic.repair.dto.WorkerShiftSaveDTO;
import com.bluemalic.repair.entity.SysUser;
import com.bluemalic.repair.entity.WorkerShift;
import com.bluemalic.repair.mapper.SysUserMapper;
import com.bluemalic.repair.mapper.WorkerShiftMapper;
import com.bluemalic.repair.service.AuditService;
import com.bluemalic.repair.service.CurrentTenantService;
import com.bluemalic.repair.service.WorkerShiftService;
import com.bluemalic.repair.vo.WorkerShiftVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 排班管理。
 *
 * <p><b>「没有记录」与「明确休息」不是一回事</b>：派单评分对前者记中性分（缺数据不等于差），
 * 对后者记 0 分并在候选明细里写明。所以这里保存的是**明确的值班状态**，而不是"默认在班"。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class WorkerShiftServiceImpl implements WorkerShiftService {

    private final WorkerShiftMapper workerShiftMapper;
    private final SysUserMapper sysUserMapper;
    private final CurrentTenantService currentTenantService;
    private final AuditService auditService;

    @Override
    public List<WorkerShiftVO> list(LocalDate date) {
        long tenantId = currentTenantService.requireTenantId();
        List<WorkerShift> shifts = workerShiftMapper.selectList(Wrappers.<WorkerShift>lambdaQuery()
                .eq(WorkerShift::getTenantId, tenantId)
                .eq(WorkerShift::getShiftDate, date)
                .orderByAsc(WorkerShift::getShiftType)
                .orderByAsc(WorkerShift::getWorkerId));
        if (shifts.isEmpty()) {
            return List.of();
        }
        List<Long> workerIds = shifts.stream().map(WorkerShift::getWorkerId).distinct().toList();
        Map<Long, SysUser> workers = sysUserMapper.selectList(Wrappers.<SysUser>lambdaQuery()
                        .eq(SysUser::getTenantId, tenantId)
                        .in(SysUser::getId, workerIds))
                .stream().collect(Collectors.toMap(SysUser::getId, user -> user));

        return shifts.stream().map(shift -> {
            WorkerShiftVO vo = WorkerShiftConverter.toVO(shift);
            SysUser worker = workers.get(shift.getWorkerId());
            if (worker != null) {
                vo.setUsername(worker.getUsername());
                vo.setWorkerName(Texts.nullToEmpty(worker.getRealName()));
            }
            return vo;
        }).toList();
    }

    @Override
    @Transactional
    public void save(WorkerShiftSaveDTO dto) {
        long tenantId = currentTenantService.requireTenantId();
        SysUser worker = sysUserMapper.selectList(Wrappers.<SysUser>lambdaQuery()
                        .eq(SysUser::getTenantId, tenantId)
                        .eq(SysUser::getId, dto.getWorkerId()))
                .stream().findFirst().orElse(null);
        if (worker == null || !Integer.valueOf(UserType.WORKER.getCode()).equals(worker.getUserType())
                || !Integer.valueOf(1).equals(worker.getStatus())) {
            throw new BizException(ErrorCode.PARAM_INVALID, "维修工不存在或已停用");
        }

        WorkerShift existing = workerShiftMapper.selectList(Wrappers.<WorkerShift>lambdaQuery()
                        .eq(WorkerShift::getTenantId, tenantId)
                        .eq(WorkerShift::getWorkerId, dto.getWorkerId())
                        .eq(WorkerShift::getShiftDate, dto.getShiftDate())
                        .eq(WorkerShift::getShiftType, dto.getShiftType()))
                .stream().findFirst().orElse(null);
        if (existing == null) {
            WorkerShift shift = new WorkerShift();
            shift.setTenantId(tenantId);
            shift.setWorkerId(dto.getWorkerId());
            shift.setShiftDate(dto.getShiftDate());
            shift.setShiftType(dto.getShiftType());
            shift.setStatus(dto.getStatus());
            shift.setRemark(dto.getRemark());
            try {
                workerShiftMapper.insert(shift);
            } catch (DuplicateKeyException e) {
                // 并发提交同一个 (维修工, 日期, 班次)：唯一索引兜住，改成覆盖
                updateStatus(tenantId, dto);
            }
        } else {
            updateStatus(tenantId, dto);
        }

        log.info("设置排班 workerId={} date={} shiftType={} status={} operator={}",
                dto.getWorkerId(), dto.getShiftDate(), dto.getShiftType(), dto.getStatus(),
                StpUtil.getLoginIdAsLong());
        auditService.record(AuditAction.WORKER_SHIFT_SET, AuditTarget.WORKER, dto.getWorkerId(),
                Texts.nullToEmpty(worker.getRealName()),
                "设置排班 " + dto.getShiftDate() + " " + shiftTypeText(dto.getShiftType())
                        + "：" + (Integer.valueOf(1).equals(dto.getStatus()) ? "在班" : "请假/休息"));
    }

    private void updateStatus(long tenantId, WorkerShiftSaveDTO dto) {
        workerShiftMapper.update(null, Wrappers.<WorkerShift>lambdaUpdate()
                .eq(WorkerShift::getTenantId, tenantId)
                .eq(WorkerShift::getWorkerId, dto.getWorkerId())
                .eq(WorkerShift::getShiftDate, dto.getShiftDate())
                .eq(WorkerShift::getShiftType, dto.getShiftType())
                .set(WorkerShift::getStatus, dto.getStatus())
                .set(WorkerShift::getRemark, dto.getRemark()));
    }

    private String shiftTypeText(Integer shiftType) {
        return Integer.valueOf(2).equals(shiftType) ? "夜班" : "白班";
    }
}
