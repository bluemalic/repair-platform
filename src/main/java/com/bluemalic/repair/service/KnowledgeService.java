package com.bluemalic.repair.service;

import com.bluemalic.repair.dto.KnowledgeEntryDTO;
import com.bluemalic.repair.vo.KnowledgeEntryVO;
import com.bluemalic.repair.vo.PageResult;

/** 维修知识库的维护（管理端）。师傅端的"用"在 {@link KnowledgeAdviceService}。 */
public interface KnowledgeService {

    PageResult<KnowledgeEntryVO> page(long pageNum, long pageSize, String keyword, Long categoryId, Integer status);

    KnowledgeEntryVO create(KnowledgeEntryDTO dto);

    void update(long id, KnowledgeEntryDTO dto);

    /** 启用 / 停用。**没有删除**：条目会被历史建议引用（`docs/02` §3.7）。 */
    void updateStatus(long id, Integer status);
}
