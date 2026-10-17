package com.bluemalic.repair.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.bluemalic.repair.entity.KnowledgeEntry;

/**
 * 维修知识库。
 *
 * <p>检索要用全文索引（`MATCH ... AGAINST`，ADR-018），那不是 MP 的条件构造器能表达的，
 * 所以后面会加一个自定义方法（XML 或 {@code @Select}）——**届时租户条件与状态条件都要显式写**，
 * 本表不在数据权限拦截器的覆盖名单里（AGENTS §5.6）。
 */
public interface KnowledgeEntryMapper extends BaseMapper<KnowledgeEntry> {
}
