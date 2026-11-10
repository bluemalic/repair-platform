package com.bluemalic.repair.service;

import com.bluemalic.repair.dto.WorkerGroupCreateDTO;
import com.bluemalic.repair.dto.WorkerGroupUpdateDTO;
import com.bluemalic.repair.vo.WorkerGroupVO;

import java.util.List;

/**
 * 工种分组（阶段 3 · docs/01 §4.8）。分组只影响派单评分的技能维度，
 * **不影响数据权限**——负责楼栋才决定师傅能看到哪些单。
 */
public interface WorkerGroupService {

    List<WorkerGroupVO> list();

    WorkerGroupVO create(WorkerGroupCreateDTO dto);

    void update(long id, WorkerGroupUpdateDTO dto);

    /** 逻辑删除。组内还有人、或仍被报修类别引用时拒绝。 */
    void delete(long id);

    /** 设置组内维修工（提交最终名单，空数组 = 清空）。 */
    void setWorkers(long id, List<Long> workerIds);
}
