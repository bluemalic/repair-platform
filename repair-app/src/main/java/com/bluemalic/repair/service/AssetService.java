package com.bluemalic.repair.service;

import com.bluemalic.repair.dto.AssetCreateDTO;
import com.bluemalic.repair.dto.AssetUpdateDTO;
import com.bluemalic.repair.vo.AssetVO;
import com.bluemalic.repair.vo.PageResult;

/**
 * 设备台账（阶段 3 · docs/01 §4.8）。
 *
 * <p>asset 不在数据权限拦截器的范围内（它只管 ticket / notification），所以**每条查询都显式带租户条件**。
 * 设备不参与工单状态机；工单与设备的关联（{@code ticket.asset_id}）在后续改动里接上。
 */
public interface AssetService {

    PageResult<AssetVO> page(long pageNum, long pageSize, Long buildingId, Long categoryId,
                             Integer status, String keyword);

    AssetVO detail(long id);

    AssetVO create(AssetCreateDTO dto);

    void update(long id, AssetUpdateDTO dto);

    /**
     * 删除设备。**只允许删除从未被工单引用的设备**；有工单的只能停用/报废
     * （逻辑删除后历史引用会显示空名称，和楼栋同一条理由）。
     */
    void delete(long id);
}
