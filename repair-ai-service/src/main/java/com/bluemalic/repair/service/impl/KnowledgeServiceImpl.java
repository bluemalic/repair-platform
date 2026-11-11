package com.bluemalic.repair.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bluemalic.repair.common.BizException;
import com.bluemalic.repair.common.ErrorCode;
import com.bluemalic.repair.common.Paging;
import com.bluemalic.repair.converter.KnowledgeEntryConverter;
import com.bluemalic.repair.dto.KnowledgeEntryDTO;
import com.bluemalic.repair.entity.KnowledgeEntry;
import com.bluemalic.repair.entity.TicketCategory;
import com.bluemalic.repair.mapper.KnowledgeEntryMapper;
import com.bluemalic.repair.mapper.TicketCategoryMapper;
import com.bluemalic.repair.service.CurrentTenantService;
import com.bluemalic.repair.service.KnowledgeService;
import com.bluemalic.repair.vo.KnowledgeEntryVO;
import com.bluemalic.repair.vo.PageResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 维修知识库的维护（管理端）。
 *
 * <p><b>每一条查询都显式写 `tenant_id`</b>：`knowledge_entry` 不在数据权限拦截器的覆盖名单里
 * （那张名单只有 `ticket` 与 `notification`，AGENTS §5.6）。这里漏一处，学校 A 就能读到学校 B 的知识库。
 *
 * <p><b>没有删除，只有启停</b>：条目会被历史建议引用，删掉就断了"当时凭什么给这条建议"的线索
 * （`docs/02` §3.7）。所以本类里根本没有 delete 方法。
 *
 * <p><b>刻意不进操作审计</b>：审计记的是"账号与基础数据"的写操作（`docs/01` §4.4），
 * 知识库是内容而非账号 / 字典。要纳入的话得先给 `AuditTarget` 加一类，那是一个独立决定——
 * 这里保持现状，并在 `docs/03` 写明。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KnowledgeServiceImpl implements KnowledgeService {

    private static final int STATUS_ENABLED = 1;
    private static final int STATUS_DISABLED = 0;

    private final KnowledgeEntryMapper knowledgeEntryMapper;
    private final TicketCategoryMapper ticketCategoryMapper;
    private final CurrentTenantService currentTenantService;

    @Override
    public PageResult<KnowledgeEntryVO> page(long pageNum, long pageSize, String keyword,
                                            Long categoryId, Integer status) {
        long tenantId = currentTenantService.requireTenantId();

        Page<KnowledgeEntry> page = knowledgeEntryMapper.selectPage(
                new Page<>(Paging.clamp(pageNum), Paging.clamp(pageSize)),
                Wrappers.<KnowledgeEntry>lambdaQuery()
                        .eq(KnowledgeEntry::getTenantId, tenantId)
                        .eq(categoryId != null, KnowledgeEntry::getCategoryId, categoryId)
                        .eq(status != null, KnowledgeEntry::getStatus, status)
                        // 关键词同时匹配标题与现象：管理员常常只记得"现象"里的一句话
                        .and(StringUtils.hasText(keyword), wrapper -> wrapper
                                .like(KnowledgeEntry::getTitle, keyword)
                                .or()
                                .like(KnowledgeEntry::getSymptom, keyword))
                        // 刚改过的排前面，与其它管理端列表一致
                        .orderByDesc(KnowledgeEntry::getUpdateTime)
                        .orderByDesc(KnowledgeEntry::getId));

        Map<Long, String> categoryNames = categoryNames(tenantId);
        Page<KnowledgeEntryVO> voPage = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
        voPage.setRecords(page.getRecords().stream()
                .map(entry -> KnowledgeEntryConverter.toVO(entry, categoryNames.get(entry.getCategoryId())))
                .toList());
        return PageResult.of(voPage);
    }

    @Override
    @Transactional
    public KnowledgeEntryVO create(KnowledgeEntryDTO dto) {
        long tenantId = currentTenantService.requireTenantId();
        requireCategory(tenantId, dto.getCategoryId());

        KnowledgeEntry entry = KnowledgeEntryConverter.toEntity(dto);
        entry.setTenantId(tenantId);
        knowledgeEntryMapper.insert(entry);

        // 只记"做了什么"，不记内容：条目正文可能很长，日志不该变成第二个数据库
        log.info("新增知识库条目 tenantId={} entryId={} 挂了类别={}", tenantId, entry.getId(), dto.getCategoryId());
        return KnowledgeEntryConverter.toVO(entry, categoryNames(tenantId).get(entry.getCategoryId()));
    }

    @Override
    @Transactional
    public void update(long id, KnowledgeEntryDTO dto) {
        long tenantId = currentTenantService.requireTenantId();
        KnowledgeEntry entry = requireEntry(tenantId, id);
        requireCategory(tenantId, dto.getCategoryId());

        KnowledgeEntryConverter.applyTo(entry, dto);
        knowledgeEntryMapper.updateById(entry);
        log.info("修改知识库条目 tenantId={} entryId={}", tenantId, id);
    }

    @Override
    @Transactional
    public void updateStatus(long id, Integer status) {
        long tenantId = currentTenantService.requireTenantId();
        if (status == null || (status != STATUS_ENABLED && status != STATUS_DISABLED)) {
            throw new BizException(ErrorCode.PARAM_INVALID, "状态只能是 1（启用）或 0（停用）");
        }
        KnowledgeEntry entry = requireEntry(tenantId, id);
        entry.setStatus(status);
        knowledgeEntryMapper.updateById(entry);
        log.info("知识库条目{} tenantId={} entryId={}", status == STATUS_ENABLED ? "启用" : "停用", tenantId, id);
    }

    // ==================== 校验与辅助 ====================

    /** 按 id 取本租户的条目：**租户条件在这里，不在调用方**——调用方漏写就是跨租户改别人的条目。 */
    private KnowledgeEntry requireEntry(long tenantId, long id) {
        KnowledgeEntry entry = knowledgeEntryMapper.selectOne(Wrappers.<KnowledgeEntry>lambdaQuery()
                .eq(KnowledgeEntry::getId, id)
                .eq(KnowledgeEntry::getTenantId, tenantId));
        if (entry == null) {
            // 与其它模块一致：别人的东西一律说"不存在"，不泄露它存在
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "知识库条目不存在");
        }
        return entry;
    }

    /**
     * 类别要么不填（通用条目），要么必须是**本租户**的类别。
     * 不看启用状态：停用类别下的既有条目仍然有效，否则"停用一个类别"会连带把知识库改坏。
     */
    private void requireCategory(long tenantId, Long categoryId) {
        if (categoryId == null) {
            return;
        }
        Long count = ticketCategoryMapper.selectCount(Wrappers.<TicketCategory>lambdaQuery()
                .eq(TicketCategory::getId, categoryId)
                .eq(TicketCategory::getTenantId, tenantId));
        if (count == null || count == 0) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "报修类别不存在");
        }
    }

    private Map<Long, String> categoryNames(long tenantId) {
        return ticketCategoryMapper.selectList(Wrappers.<TicketCategory>lambdaQuery()
                        .eq(TicketCategory::getTenantId, tenantId))
                .stream()
                .collect(Collectors.toMap(TicketCategory::getId, TicketCategory::getName, (a, b) -> a));
    }
}
