package com.bluemalic.repair.ai.agent.tool;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bluemalic.repair.ai.agent.AgentTool;
import com.bluemalic.repair.entity.Building;
import com.bluemalic.repair.mapper.BuildingMapper;
import com.bluemalic.repair.service.CurrentTenantService;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Map;

/**
 * 工具：列出**本租户**启用中的楼栋。
 *
 * <p>存在的理由：模型不知道 A 校有哪些楼栋，而受理必须把"用户说的楼栋名"落成 `buildingId`。
 * 没有这个工具，它只能猜 id——猜错就是一张派给错误楼栋的单。
 *
 * <p><b>为什么带 `keyword`</b>：一所学校几十栋楼全塞回给模型既费 token 又干扰判断；
 * 让模型先按用户提到的字眼过滤一次，是它自己就能做好的事。
 *
 * <p><b>租户条件是显式写的</b>：`building` 不在数据权限拦截器的覆盖名单里（那张名单只有
 * `ticket` 与 `notification`），所以这里**必须**自己带 `tenant_id`——少一行就是跨租户读
 * （AGENTS §5.6 第三条）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class BuildingListTool implements AgentTool {

    private static final int MAX_RESULTS = 50;

    private final BuildingMapper buildingMapper;
    private final CurrentTenantService currentTenantService;
    private final ObjectMapper objectMapper;

    @Override
    public String name() {
        return "listBuildings";
    }

    @Override
    public String description() {
        return "列出本校启用中的楼栋（含 id 与名称）。用户提到楼栋名时先调它，把名字对应成 id；"
                + "可用 keyword 按名称片段过滤（如\"1 号\"）。返回 JSON 数组。";
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
            var query = Wrappers.<Building>lambdaQuery()
                    .eq(Building::getTenantId, tenantId)
                    .eq(Building::getStatus, 1);
            if (StringUtils.hasText(keyword)) {
                query.like(Building::getName, keyword);
            }
            var rows = buildingMapper.selectList(query.orderByAsc(Building::getSort)).stream()
                    .limit(MAX_RESULTS)
                    .map(b -> Map.of("id", b.getId(), "name", b.getName()))
                    .toList();
            return objectMapper.writeValueAsString(rows);
        } catch (Exception e) {
            // 工具失败不打断对话：把"拿不到"告诉模型，让它改为问用户（AgentTool 的约定）
            log.warn("工具 listBuildings 执行失败 err={}", e.getMessage());
            return "查询楼栋失败。请改为让用户直接提供楼栋名称。";
        }
    }
}
