package com.bluemalic.repair;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bluemalic.repair.entity.KnowledgeEntry;
import com.bluemalic.repair.entity.SysUser;
import com.bluemalic.repair.entity.SysUserRole;
import com.bluemalic.repair.entity.TicketCategory;
import com.bluemalic.repair.mapper.KnowledgeEntryMapper;
import com.bluemalic.repair.mapper.SysUserMapper;
import com.bluemalic.repair.mapper.SysUserRoleMapper;
import com.bluemalic.repair.mapper.TicketCategoryMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 管理端维修知识库的维护（阶段 2 · docs/03 §5.4）。
 *
 * <p>这个类盯的是**租户隔离**：{@code knowledge_entry} 不在数据权限拦截器的覆盖名单里
 * （那张名单只有 {@code ticket} 与 {@code notification}），所以每条 SQL 的 {@code tenant_id}
 * 都得自己写——写漏了不会有任何报错，只会读到别校的知识库。这条靠人看代码是看不出来的，
 * 只能靠测试钉住。
 */
@IntegrationTest
class KnowledgeAdminTest {

    private static final String PASSWORD = "Test@123456";
    private static final long ROLE_WORKER = 2L;
    private static final long ROLE_ADMIN = 3L;
    private static final long TENANT = 1L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private SysUserRoleMapper sysUserRoleMapper;

    @Autowired
    private TicketCategoryMapper ticketCategoryMapper;

    @Autowired
    private KnowledgeEntryMapper knowledgeEntryMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private String admin;
    private String worker;
    private long waterCategoryId;

    @BeforeEach
    void setUp() throws Exception {
        admin = givenToken("test-kb-admin", 3, ROLE_ADMIN);
        worker = givenToken("test-kb-worker", 2, ROLE_WORKER);
        waterCategoryId = ticketCategoryMapper.selectOne(Wrappers.<TicketCategory>lambdaQuery()
                .eq(TicketCategory::getTenantId, TENANT).eq(TicketCategory::getName, "水电")).getId();
    }

    @Test
    void 新增修改停用一条龙() throws Exception {
        long id = create(admin, dto("测试条目：水龙头滴水", "水龙头关不严，一直在滴水", 1L,
                "拧紧阀芯\\n更换密封圈", "滴水 漏水 水龙头"));

        // 列表能查到，并且服务端补了类别名（前端不必再查字典）
        JsonNode row = findInList(id);
        assertThat(row.path("title").asText()).isEqualTo("测试条目：水龙头滴水");
        assertThat(row.path("categoryName").asText()).isEqualTo("水电");
        assertThat(row.path("status").asInt()).isEqualTo(1);

        // 修改：字段全量覆盖
        Map<String, Object> edit = dto("测试条目：水龙头滴水（改）", "水龙头关不严，一直在滴水", 1L,
                "拆开阀芯看密封圈", "滴水 漏水 水龙头 阀芯");
        edit.put("status", 0);
        mockMvc.perform(put("/api/admin/knowledge/" + id)
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(edit)))
                .andExpect(jsonPath("$.code").value(0));

        JsonNode updated = findInList(id);
        assertThat(updated.path("title").asText()).isEqualTo("测试条目：水龙头滴水（改）");
        assertThat(updated.path("status").asInt()).as("修改时 status 一起覆盖").isZero();

        // 单独启停回来
        mockMvc.perform(put("/api/admin/knowledge/" + id + "/status")
                        .header("Authorization", "Bearer " + admin)
                        .param("status", "1"))
                .andExpect(jsonPath("$.code").value(0));
        assertThat(findInList(id).path("status").asInt()).isEqualTo(1);
    }

    @Test
    void 列表可以按关键词与类别筛选() throws Exception {
        long id = create(admin, dto("测试条目：特殊的荧光灯管", "灯管两端发黑，一开就闪", 1L, "换灯管", "灯管 闪烁"));

        // 关键词命中"现象"里的字
        JsonNode byKeyword = page(admin, "keyword", "荧光");
        assertThat(collectIds(byKeyword)).contains(id);

        // 类别筛选：挂在水电下的能按水电筛出来
        JsonNode byCategory = page(admin, "categoryId", String.valueOf(waterCategoryId));
        assertThat(collectIds(byCategory)).contains(id);
    }

    /** 核心不变量：别的租户的条目，读不到、也改不了（本表不在拦截器名单里，全靠 SQL 自己写租户条件）。 */
    @Test
    void 别的租户的条目读不到也改不了() throws Exception {
        KnowledgeEntry other = new KnowledgeEntry();
        other.setTenantId(99L);
        other.setTitle("别的学校的条目");
        other.setSymptom("不该出现在这个租户的列表里");
        other.setSolution("……");
        other.setStatus(1);
        knowledgeEntryMapper.insert(other);

        assertThat(collectIds(page(admin, "keyword", "别的学校")))
                .as("列表不该出现别的租户的条目").doesNotContain(other.getId());

        // 改也改不了：对外一律说"不存在"（不泄露它存在）
        mockMvc.perform(put("/api/admin/knowledge/" + other.getId())
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto("改名", "现象", 1L, "步骤", null))))
                .andExpect(jsonPath("$.code").value(10006));

        mockMvc.perform(put("/api/admin/knowledge/" + other.getId() + "/status")
                        .header("Authorization", "Bearer " + admin)
                        .param("status", "0"))
                .andExpect(jsonPath("$.code").value(10006));

        // 原样未动
        assertThat(knowledgeEntryMapper.selectById(other.getId()).getTitle()).isEqualTo("别的学校的条目");
    }

    @Test
    void 挂到不存在的类别会被拒() throws Exception {
        mockMvc.perform(post("/api/admin/knowledge")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto("标题", "现象", 99999999L, "步骤", null))))
                .andExpect(jsonPath("$.code").value(10006));
    }

    @Test
    void 必填项缺失会被拒() throws Exception {
        Map<String, Object> empty = new HashMap<>();
        empty.put("title", "  ");
        empty.put("symptom", "现象");
        empty.put("solution", "步骤");
        mockMvc.perform(post("/api/admin/knowledge")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(empty)))
                .andExpect(jsonPath("$.code").value(10001));
    }

    /** 知识库维护是后勤的活（knowledge:manage），师傅只有"要建议"的权利。 */
    @Test
    void 师傅不能维护知识库() throws Exception {
        mockMvc.perform(get("/api/admin/knowledge").header("Authorization", "Bearer " + worker))
                .andExpect(jsonPath("$.code").value(10003));
        mockMvc.perform(post("/api/admin/knowledge")
                        .header("Authorization", "Bearer " + worker)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(dto("标题", "现象", 1L, "步骤", null))))
                .andExpect(jsonPath("$.code").value(10003));
    }

    // ==================== 工具 ====================

    private Map<String, Object> dto(String title, String symptom, Long categoryId, String solution, String tags) {
        Map<String, Object> body = new HashMap<>();
        body.put("title", title);
        body.put("symptom", symptom);
        body.put("solution", solution);
        body.put("categoryId", categoryId);
        body.put("tags", tags);
        body.put("status", 1);
        return body;
    }

    private long create(String token, Map<String, Object> body) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/admin/knowledge")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(body)))
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        return json(result).path("data").path("id").asLong();
    }

    private JsonNode findInList(long id) throws Exception {
        JsonNode page = page(admin, "keyword", null);
        for (JsonNode row : page.path("list")) {
            if (row.path("id").asLong() == id) {
                return row;
            }
        }
        throw new AssertionError("列表里没有 id=" + id + " 的条目");
    }

    private JsonNode page(String token, String filterName, String filterValue) throws Exception {
        var request = get("/api/admin/knowledge")
                .header("Authorization", "Bearer " + token)
                .param("pageSize", "100");
        if (filterValue != null) {
            request = request.param(filterName, filterValue);
        }
        return json(mockMvc.perform(request).andExpect(jsonPath("$.code").value(0)).andReturn()).path("data");
    }

    private java.util.List<Long> collectIds(JsonNode page) {
        java.util.List<Long> ids = new java.util.ArrayList<>();
        for (JsonNode row : page.path("list")) {
            ids.add(row.path("id").asLong());
        }
        return ids;
    }

    private String givenToken(String username, int userType, long roleId) throws Exception {
        SysUser user = new SysUser();
        user.setTenantId(TENANT);
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setRealName(username);
        user.setUserType(userType);
        user.setStatus(1);
        sysUserMapper.insert(user);

        SysUserRole link = new SysUserRole();
        link.setUserId(user.getId());
        link.setRoleId(roleId);
        sysUserRoleMapper.insert(link);

        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "tenantCode", "gdou", "username", username, "password", PASSWORD))))
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        return json(result).path("data").path("tokenValue").asText();
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }
}
