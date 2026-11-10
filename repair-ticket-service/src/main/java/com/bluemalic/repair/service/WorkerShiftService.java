package com.bluemalic.repair.service;

import com.bluemalic.repair.dto.WorkerShiftSaveDTO;
import com.bluemalic.repair.vo.WorkerShiftVO;

import java.time.LocalDate;
import java.util.List;

/** 排班（阶段 3 · docs/01 §4.8）：派单候选默认把明确请假/休息的人排在最后。 */
public interface WorkerShiftService {

    /** 某天的排班行；没有记录的维修工不出现在这里（与「明确休息」是两件事）。 */
    List<WorkerShiftVO> list(LocalDate date);

    /** 按 (维修工, 日期, 班次) 唯一，重复提交即覆盖。 */
    void save(WorkerShiftSaveDTO dto);
}
