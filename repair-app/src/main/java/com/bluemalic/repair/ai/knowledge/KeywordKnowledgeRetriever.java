package com.bluemalic.repair.ai.knowledge;

import com.bluemalic.repair.mapper.KnowledgeEntryMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;

/**
 * 关键词检索（默认实现）：MySQL 8 的 ngram 全文索引，零新增依赖（ADR-018）。
 *
 * <p>它为什么够用：知识库是"一所学校几十条"，而 ngram 把中文切成 2-gram 建倒排后，
 * "宿舍门锁转不动" 这种口语说法也能只命中该命中的那条（实测数据记在 ADR-018）。
 * 它最大的弱点——**同义词召回**（"渗水" 命中不了 "漏水"）——交给条目的 {@code tags} 人工补，
 * 这是刻意留给人的旋钮，不是漏做。
 *
 * <p>配置 {@code repair.knowledge.retriever=keyword}（不配也是它）。将来加向量实现时，
 * 这个类留着当降级路径与对照物——**别删**：向量服务挂掉时它是最省事的兜底。
 */
@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "repair.knowledge.retriever", havingValue = "keyword", matchIfMissing = true)
public class KeywordKnowledgeRetriever implements KnowledgeRetriever {

    /** 调用方要几条就几条，但封顶：塞进提示词的参考资料越多越贵、也越容易让模型跑题 */
    private static final int MAX_LIMIT = 5;

    private final KnowledgeEntryMapper knowledgeEntryMapper;

    @Override
    public List<KnowledgeHit> search(long tenantId, String text, Long categoryId, int limit) {
        if (!StringUtils.hasText(text) || limit <= 0) {
            // 空描述不查库：**不**把它理解成"匹配所有条目"——那会让建议建立在无关资料上
            return List.of();
        }
        int capped = Math.min(limit, MAX_LIMIT);
        List<KnowledgeHit> hits = knowledgeEntryMapper.searchByKeyword(tenantId, text.trim(), categoryId, capped);
        log.info("知识库检索 tenantId={} categoryId={} 命中={} 检索方式=keyword", tenantId, categoryId, hits.size());
        return hits;
    }

    @Override
    public String name() {
        return "keyword";
    }
}
