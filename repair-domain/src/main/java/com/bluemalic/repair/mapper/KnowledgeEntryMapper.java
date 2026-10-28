package com.bluemalic.repair.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bluemalic.repair.ai.knowledge.KnowledgeHit;
import com.bluemalic.repair.entity.KnowledgeEntry;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 维修知识库。
 *
 * <p>{@link #searchByKeyword} 是唯一一条自定义 SQL（全文检索不是条件构造器能表达的，
 * 见 `src/main/resources/mapper/KnowledgeEntryMapper.xml`）。**租户条件与状态条件都在 SQL 里显式写**：
 * 本表不在数据权限拦截器的覆盖名单里（AGENTS §5.6），漏一处就是跨租户读别校的知识库。
 */
public interface KnowledgeEntryMapper extends BaseMapper<KnowledgeEntry> {

    /**
     * 中文关键词检索（ngram 全文索引，ADR-018）。
     *
     * @param categoryId 可空。非空时只取"该类别 + 通用条目"，同类别条目排前面
     */
    List<KnowledgeHit> searchByKeyword(@Param("tenantId") long tenantId,
                                       @Param("query") String query,
                                       @Param("categoryId") Long categoryId,
                                       @Param("limit") int limit);
}
