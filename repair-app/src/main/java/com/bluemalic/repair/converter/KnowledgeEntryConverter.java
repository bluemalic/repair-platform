package com.bluemalic.repair.converter;

import com.bluemalic.repair.dto.KnowledgeEntryDTO;
import com.bluemalic.repair.entity.KnowledgeEntry;
import com.bluemalic.repair.vo.KnowledgeEntryVO;

/** {@code KnowledgeEntry} ↔ DTO / VO。 */
public class KnowledgeEntryConverter {

    /** 工具类，禁止实例化。 */
    private KnowledgeEntryConverter() {
    }

    public static KnowledgeEntryVO toVO(KnowledgeEntry entry, String categoryName) {
        if (entry == null) {
            return null;
        }
        KnowledgeEntryVO vo = new KnowledgeEntryVO();
        vo.setId(entry.getId());
        vo.setTitle(entry.getTitle());
        vo.setSymptom(entry.getSymptom());
        vo.setSolution(entry.getSolution());
        vo.setCategoryId(entry.getCategoryId());
        vo.setCategoryName(categoryName);
        vo.setTags(entry.getTags());
        vo.setStatus(entry.getStatus());
        vo.setUpdateTime(entry.getUpdateTime());
        return vo;
    }

    /**
     * 入参 → 实体。租户由调用方设置（Service 从登录态取），**转换器不碰租户**——
     * 让"哪个租户"这件事只有一个来源。
     */
    public static KnowledgeEntry toEntity(KnowledgeEntryDTO dto) {
        KnowledgeEntry entry = new KnowledgeEntry();
        applyTo(entry, dto);
        return entry;
    }

    /** 修改用：字段逐个覆盖到已有实体上（不改 id / tenantId / deleted）。 */
    public static void applyTo(KnowledgeEntry entry, KnowledgeEntryDTO dto) {
        entry.setTitle(dto.getTitle().trim());
        entry.setSymptom(dto.getSymptom().trim());
        entry.setSolution(dto.getSolution().trim());
        entry.setCategoryId(dto.getCategoryId());
        entry.setTags(dto.getTags() == null || dto.getTags().isBlank() ? null : dto.getTags().trim());
        entry.setStatus(dto.getStatus() == null ? 1 : dto.getStatus());
    }
}
