package com.bluemalic.repair.ai.agent.tool;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bluemalic.repair.ai.agent.AgentTool;
import com.bluemalic.repair.entity.TicketCategory;
import com.bluemalic.repair.mapper.TicketCategoryMapper;
import com.bluemalic.repair.service.CurrentTenantService;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 工具：列出**本租户**启用中的报修类别（含该类别默认的紧急度）。
 *
 * <p>受理与分诊都靠它：模型必须从**这所学校自己的**类别表里挑一个，而不是自己编一个类别名
 * ——类别是数据（每个学校可以不一样），不是常识。带上 `defaultUrgency` 是为了让模型在
 * "用户没说多急"时有依据地选紧急度，而不是凭感觉。
 *
 * <p>租户条件显式写，理由同 {@link BuildingListTool}（`ticket_category` 不在拦截器名单里）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CategoryListTool implements AgentTool {

    private static final int MAX_RESULTS = 50;

    private final TicketCategoryMapper ticketCategoryMapper;
    private final CurrentTenantService currentTenantService;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "listCategories";
    }

    @Override
    public String description() {
        return "列出本校启用中的报修类别（含 id、名称与默认紧急度 1 一般 / 2 紧急 / 3 特急）。"
                + "判断报修属于哪一类时必须从这里挑，不要自己编类别名；可用 keyword 过滤。返回 JSON 数组。";
    }

    @Override
    public ToolSpecification spec() {
        return ToolSpecification.builder()
                .name(name())
                .description(description())
                .parameters(JsonObjectSchema.builder()
                        .addStringProperty("keyword", "可选。按名称片段过滤，例如填「1 号」只返回名字里含它的那些")
                        .build())
                .build();
    }

    @Override
    public String execute(String argumentsJson) {
        try {
            Long tenantId = currentTenantService.requireTenantId();
            String keyword = ToolArgs.stringArg(argumentsJson, "keyword");
            var query = Wrappers.<TicketCategory>lambdaQuery()
                    .eq(TicketCategory::getTenantId, tenantId)
                    .eq(TicketCategory::getStatus, 1);
            if (StringUtils.hasText(keyword)) {
                query.like(TicketCategory::getName, keyword);
            }
            var rows = ticketCategoryMapper.selectList(query.orderByAsc(TicketCategory::getSort)).stream()
                    .limit(MAX_RESULTS)
                    .map(c -> {
                        // LinkedHashMap 而不是 Map.of：Map.of 不接受 null，而 defaultUrgency 可能为空
                        Map<String, Object> item = new LinkedHashMap<>();
                        item.put("id", c.getId());
                        item.put("name", c.getName());
                        item.put("defaultUrgency", c.getDefaultUrgency());
                        return item;
                    })
                    .toList();
            return objectMapper.writeValueAsString(rows);
        } catch (Exception e) {
            log.warn("工具 listCategories 执行失败 err={}", e.getMessage());
            return "查询报修类别失败。请改为让用户直接描述问题，类别由后勤在派单时确定。";
        }
    }
}
