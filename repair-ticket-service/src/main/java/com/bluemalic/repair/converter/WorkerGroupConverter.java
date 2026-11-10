package com.bluemalic.repair.converter;

import com.bluemalic.repair.entity.WorkerGroup;
import com.bluemalic.repair.vo.WorkerGroupVO;

import java.util.List;

/** {@code WorkerGroup} 实体 → {@link WorkerGroupVO}；成员名单由 Service 批量补。 */
public class WorkerGroupConverter {

    private WorkerGroupConverter() {
    }

    public static WorkerGroupVO toVO(WorkerGroup group, List<Long> workerIds) {
        if (group == null) {
            return null;
        }
        WorkerGroupVO vo = new WorkerGroupVO();
        vo.setId(group.getId());
        vo.setName(group.getName());
        vo.setSort(group.getSort());
        vo.setStatus(group.getStatus());
        vo.setWorkerIds(workerIds);
        vo.setWorkerCount(workerIds.size());
        return vo;
    }
}
