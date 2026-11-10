package com.bluemalic.repair.service;

import com.bluemalic.repair.dto.RepairCodeCreateDTO;
import com.bluemalic.repair.dto.RepairCodeUpdateDTO;
import com.bluemalic.repair.vo.PageResult;
import com.bluemalic.repair.vo.RepairCodeDetailVO;

/**
 * 报修码管理（后勤端）。码是"位置码"（ADR-004）：一条记录 = 一个房间的一把码。
 *
 * <p>没有删除接口（契约里也没有）：不再使用的码**停用**即可，历史与排查都还看得到。
 * 房间填错了用修改接口改，码要换用"重新生成"。
 */
public interface RepairCodeService {

    PageResult<RepairCodeDetailVO> page(long pageNum, long pageSize, Long buildingId, Integer status);

    /** 为指定楼栋 + 房间生成随机短码；同一房间不允许存在第二条码（一房一码）。 */
    RepairCodeDetailVO create(RepairCodeCreateDTO dto);

    /** 改房间号 / 启停 / 重新生成码。 */
    void update(long id, RepairCodeUpdateDTO dto);
}
