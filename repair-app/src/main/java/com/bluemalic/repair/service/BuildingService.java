package com.bluemalic.repair.service;

import com.bluemalic.repair.dto.BuildingCreateDTO;
import com.bluemalic.repair.dto.BuildingUpdateDTO;
import com.bluemalic.repair.vo.BuildingVO;

import java.util.List;

/**
 * 楼栋管理（后勤端）。租户内的字典数据。
 *
 * <p><b>列表不分页</b>：一个学校几十栋楼封顶，前端的下拉框需要一次拿全；分页反而要前端多写一轮
 * 翻页逻辑。这个判断只对字典表成立——维修工数量会随招聘增长，所以那边是分页的。
 */
public interface BuildingService {

    /** @param status 可选筛选：1启用 0停用；不传返回全部（管理端要能看到已停用的并重新启用） */
    List<BuildingVO> list(Integer status);

    BuildingVO create(BuildingCreateDTO dto);

    void update(long id, BuildingUpdateDTO dto);

    /** 逻辑删除。只允许删除**从未被引用**的楼栋，理由见实现里的注释。 */
    void delete(long id);
}
