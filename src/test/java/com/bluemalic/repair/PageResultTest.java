package com.bluemalic.repair;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.bluemalic.repair.entity.Building;
import com.bluemalic.repair.mapper.BuildingMapper;
import com.bluemalic.repair.vo.PageResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分页的两件事各自都要验证，它们坏起来的表现完全不同：
 *
 * <ul>
 *   <li>分页插件没注册 → 不报错，只是把全表查出来再内存分页（数据量大才暴露）</li>
 *   <li>序列化规则没生效 → 计数字段变成字符串，前端分页组件直接不工作</li>
 * </ul>
 */
@SpringBootTest
@Transactional
class PageResultTest {

    @Autowired
    private BuildingMapper buildingMapper;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void countsAreNumbersWhileIdsAreStrings() throws Exception {
        Building building = new Building();
        building.setId(1857392012345678L);
        building.setName("3号楼");

        Page<Building> page = new Page<>(1, 10);
        page.setTotal(137);
        page.setRecords(List.of(building));

        JsonNode node = objectMapper.readTree(objectMapper.writeValueAsString(PageResult.of(page)));

        assertThat(node.get("total").isNumber())
                .as("total 必须是数字：前端分页组件要求 Number，且它不可能接近 2^53").isTrue();
        assertThat(node.get("pages").isNumber()).isTrue();
        assertThat(node.get("pageNum").isNumber()).isTrue();
        assertThat(node.get("pageSize").isNumber()).isTrue();
        assertThat(node.get("pages").asLong()).isEqualTo(14);

        // 同一个响应里，业务 ID 仍按全局规则输出字符串 —— 证明这个例外只开在计数字段上
        JsonNode id = node.get("list").get(0).get("id");
        assertThat(id.isTextual())
                .as("雪花 ID 必须序列化为字符串，否则前端解析后精度丢失").isTrue();
        assertThat(id.asText()).isEqualTo("1857392012345678");
    }

    @Test
    void paginationInterceptorAppliesLimit() {
        Page<Building> page = buildingMapper.selectPage(new Page<>(1, 2), null);

        assertThat(page.getTotal())
                .as("种子数据的楼栋要多于 2 条，否则这个测试证明不了任何事").isGreaterThan(2);
        assertThat(page.getRecords())
                .as("只返回 2 条说明 LIMIT 生效；返回全部则说明分页插件没注册").hasSize(2);
    }
}
