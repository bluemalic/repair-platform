package com.bluemalic.repair.service;

import com.bluemalic.repair.dto.WorkerBuildingsDTO;
import com.bluemalic.repair.dto.WorkerCreateDTO;
import com.bluemalic.repair.dto.WorkerUpdateDTO;
import com.bluemalic.repair.vo.PageResult;
import com.bluemalic.repair.vo.WorkerVO;

/**
 * 维修工管理（后勤端）。数据范围固定为"当前租户"，见实现里的说明。
 *
 * <p>没有删除：师傅记录要被历史工单与 {@code ticket_log} 引用，删掉就说不清了，停用即可。
 */
public interface WorkerService {

    PageResult<WorkerVO> page(long pageNum, long pageSize, Integer status, String keyword);

    WorkerVO create(WorkerCreateDTO dto);

    void update(long id, WorkerUpdateDTO dto);

    /** 设置负责楼栋（全量替换）。这是数据权限的物理依据，见 ADR-002 / ADR-008。 */
    void setBuildings(long id, WorkerBuildingsDTO dto);
}
