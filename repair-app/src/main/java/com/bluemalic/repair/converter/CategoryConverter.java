package com.bluemalic.repair.converter;

import com.bluemalic.repair.entity.TicketCategory;
import com.bluemalic.repair.vo.CategoryVO;

/**
 * {@code TicketCategory} 实体 → {@link CategoryVO}。
 */
public class CategoryConverter {

    /** 工具类，禁止实例化。 */
    private CategoryConverter() {
    }

    public static CategoryVO toVO(TicketCategory category) {
        if (category == null) {
            return null;
        }
        CategoryVO vo = new CategoryVO();
        vo.setId(category.getId());
        vo.setName(category.getName());
        vo.setDefaultUrgency(category.getDefaultUrgency());
        vo.setSort(category.getSort());
        vo.setStatus(category.getStatus());
        return vo;
    }
}
