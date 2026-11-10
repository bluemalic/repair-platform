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

    /**
     * 启用中的类别（学生报修表单 / H5 用）。与 {@link #list(Integer)} 分开是为了能**只缓存这一条**：
     * 它的语义固定（"能选的类别"）、调用极频繁，而管理端带筛选的查询不适合缓存。
     *
     * @param tenantId 显式传入而不是在实现里取登录态——**缓存 key 必须含租户**，
     *                 漏了就会把 A 校的字典返给 B 校（跨租户泄露，ADR-020）
     */
    List<CategoryVO> listEnabled(long tenantId);

    CategoryVO create(CategoryCreateDTO dto);

    void update(long id, CategoryUpdateDTO dto);

    /** 逻辑删除。只允许删除**从未被引用**的类别，理由见实现里的注释。 */
    void delete(long id);
}
