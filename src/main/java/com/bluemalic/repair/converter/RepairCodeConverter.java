package com.bluemalic.repair.converter;

import com.bluemalic.repair.entity.RepairCode;
import com.bluemalic.repair.vo.RepairCodeDetailVO;

/**
 * {@code RepairCode} 实体 → {@link RepairCodeDetailVO}。
 *
 * <p>楼栋名由调用方查好传进来（列表接口批量查一次），不在这里回库——转换器会查库，
 * 列表就会退化成 N+1。这与 {@code WorkerConverter} 是同一条理由。
 */
public class RepairCodeConverter {

    /** 工具类，禁止实例化。 */
    private RepairCodeConverter() {
    }

    public static RepairCodeDetailVO toVO(RepairCode repairCode, String buildingName) {
        if (repairCode == null) {
            return null;
        }
        RepairCodeDetailVO vo = new RepairCodeDetailVO();
        vo.setId(repairCode.getId());
        vo.setCode(repairCode.getCode());
        vo.setBuildingId(repairCode.getBuildingId());
        vo.setBuildingName(buildingName);
        vo.setRoom(repairCode.getRoom());
        vo.setStatus(repairCode.getStatus());
        vo.setCreateTime(repairCode.getCreateTime());
        return vo;
    }
}
