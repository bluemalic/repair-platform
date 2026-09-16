package com.bluemalic.repair.service;

import com.bluemalic.repair.dto.CategoryCreateDTO;
import com.bluemalic.repair.dto.CategoryUpdateDTO;
import com.bluemalic.repair.vo.CategoryVO;

import java.util.List;

/**
 * 报修类别管理（后勤端）。租户内的字典数据，列表不分页的理由同 {@link BuildingService}。
 */
public interface CategoryService {

    /** @param status 可选筛选：1启用 0停用；不传返回全部 */
    List<CategoryVO> list(Integer status);

    CategoryVO create(CategoryCreateDTO dto);

    void update(long id, CategoryUpdateDTO dto);

    /** 逻辑删除。只允许删除**从未被引用**的类别，理由见实现里的注释。 */
    void delete(long id);
}
