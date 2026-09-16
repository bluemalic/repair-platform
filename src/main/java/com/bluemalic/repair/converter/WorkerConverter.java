package com.bluemalic.repair.converter;

import com.bluemalic.repair.entity.SysUser;
import com.bluemalic.repair.vo.WorkerVO;

import java.util.List;

/**
 * 维修工（{@code SysUser}）→ {@link WorkerVO} 的转换。
 *
 * <p>除了 1:1 的字段复制，还要带上"负责楼栋"：它是另一张表（`worker_building`）的数据，
 * 由调用方**批量查好后传进来**，不在这里回库——转换器一旦会查库，列表接口就会变成
 * 每行一次查询的 N+1（照 {@link TicketConverter} 处理楼栋名 / 类别名的做法）。
 */
public class WorkerConverter {

    /** 工具类，禁止实例化。 */
    private WorkerConverter() {
    }

    public static WorkerVO toVO(SysUser worker, List<Long> buildingIds, List<String> buildingNames) {
        if (worker == null) {
            return null;
        }
        WorkerVO vo = new WorkerVO();
        vo.setId(worker.getId());
        vo.setUsername(worker.getUsername());
        vo.setRealName(worker.getRealName());
        vo.setPhone(worker.getPhone());
        vo.setStatus(worker.getStatus());
        vo.setBuildingIds(buildingIds);
        vo.setBuildingNames(buildingNames);
        return vo;
    }
}
