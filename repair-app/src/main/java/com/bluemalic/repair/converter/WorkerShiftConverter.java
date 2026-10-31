package com.bluemalic.repair.converter;

import com.bluemalic.repair.entity.WorkerShift;
import com.bluemalic.repair.vo.WorkerShiftVO;

/** {@code WorkerShift} 实体 → {@link WorkerShiftVO}；工号/姓名由 Service 批量补。 */
public class WorkerShiftConverter {

    private WorkerShiftConverter() {
    }

    public static WorkerShiftVO toVO(WorkerShift shift) {
        if (shift == null) {
            return null;
        }
        WorkerShiftVO vo = new WorkerShiftVO();
        vo.setId(shift.getId());
        vo.setWorkerId(shift.getWorkerId());
        vo.setShiftDate(shift.getShiftDate());
        vo.setShiftType(shift.getShiftType());
        vo.setStatus(shift.getStatus());
        vo.setRemark(shift.getRemark());
        return vo;
    }
}
