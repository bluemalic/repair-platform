package com.bluemalic.repair.converter;

import com.bluemalic.repair.entity.Building;
import com.bluemalic.repair.vo.BuildingVO;

/**
 * {@code Building} 实体 → {@link BuildingVO}。
 *
 * <p>纯 1:1 复制：字典表没有关联数据要补，所以不像维修工那样需要传楼栋列表进来。
 */
public class BuildingConverter {

    /** 工具类，禁止实例化。 */
    private BuildingConverter() {
    }

    public static BuildingVO toVO(Building building) {
        if (building == null) {
            return null;
        }
        BuildingVO vo = new BuildingVO();
        vo.setId(building.getId());
        vo.setName(building.getName());
        vo.setArea(building.getArea());
        vo.setSort(building.getSort());
        vo.setStatus(building.getStatus());
        return vo;
    }
}
