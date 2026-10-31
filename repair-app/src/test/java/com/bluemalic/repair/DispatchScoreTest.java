package com.bluemalic.repair;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bluemalic.repair.entity.Building;
import com.bluemalic.repair.entity.SysUser;
import com.bluemalic.repair.entity.SysUserRole;
import com.bluemalic.repair.entity.Ticket;
import com.bluemalic.repair.entity.TicketCategory;
import com.bluemalic.repair.entity.TicketEvaluation;
import com.bluemalic.repair.entity.WorkerBuilding;
import com.bluemalic.repair.mapper.BuildingMapper;
import com.bluemalic.repair.mapper.SysUserMapper;
import com.bluemalic.repair.mapper.SysUserRoleMapper;
import com.bluemalic.repair.mapper.TicketCategoryMapper;
import com.bluemalic.repair.mapper.TicketEvaluationMapper;
import com.bluemalic.repair.mapper.TicketMapper;
import com.bluemalic.repair.mapper.WorkerBuildingMapper;
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
import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 派单评分（阶段 3 · ADR-019）。
 *
 * <p>这个类盯四件事，都是"错了排序就会误导人"的地方：
 * <ol>
 *   <li>**缺数据不等于差**：没维护技能 / 没评价 → 中性 0.5；正是"新师傅不该永远垫底"这条规则</li>
 *   <li>**四项明细齐全且可解释**：每项都有得分、权重、人话说明；理由是明细拼出来的</li>
 *   <li>**距离是楼栋归属的代理**：负责该楼栋 1.0、同区 0.6、其余 0.2——不是真实里程</li>
 *   <li>**只排序、不写库**：调完候选接口，工单原样不动；候选里也只有本租户的启用师傅</li>
 * </ol>
 */
@IntegrationTest
class DispatchScoreTest {

    private static final String PASSWORD = "Test@123456";
    private static final long ROLE_STUDENT = 1L;
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
    private BuildingMapper buildingMapper;

    @Autowired
    private TicketCategoryMapper ticketCategoryMapper;

    @Autowired
    private TicketMapper ticketMapper;

    @Autowired
    private TicketEvaluationMapper ticketEvaluationMapper;

    @Autowired
    private WorkerBuildingMapper workerBuildingMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private String admin;
    private String student;
    private long studentId;
    private long waterCategoryId;
    private long building1;
    /** 一栋同区楼栋、一栋跨区楼栋（种子：东区 1/2 号楼、西区 3/4 号楼） */
    private long buildingSameArea;
    private long buildingOtherArea;
    private long skilledWorkerId;
    private long otherWorkerId;

    @BeforeEach
    void setUp() throws Exception {
        admin = givenToken("test-dispatch-admin", 3, ROLE_ADMIN);
        student = givenToken("test-dispatch-student", 1, ROLE_STUDENT);
        studentId = sysUserMapper.selectOne(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getUsername, "test-dispatch-student")).getId();
        waterCategoryId = ticketCategoryMapper.selectOne(Wrappers.<TicketCategory>lambdaQuery()
                .eq(TicketCategory::getTenantId, TENANT).eq(TicketCategory::getName, "水电")).getId();

        // 不假设楼栋名与区域（本机库被演示重置改建过：1/2/3 号楼可能全是"东区"）。
        // 按**实际数据**挑一栋同区、一栋跨区；没有跨区的就造一栋（事务回滚，不留痕）
        java.util.List<Building> allBuildings = buildingMapper.selectList(Wrappers.<Building>lambdaQuery()
                .eq(Building::getTenantId, TENANT).orderByAsc(Building::getId));
        Building first = allBuildings.get(0);
        building1 = first.getId();
        String area = first.getArea();
        buildingSameArea = allBuildings.stream()
                .filter(b -> !b.getId().equals(building1) && java.util.Objects.equals(b.getArea(), area))
                .map(Building::getId).findFirst()
                .orElseGet(() -> insertBuilding("测试同区楼", area));
        buildingOtherArea = allBuildings.stream()
                .filter(b -> !java.util.Objects.equals(b.getArea(), area))
                .map(Building::getId).findFirst()
                .orElseGet(() -> insertBuilding("测试跨区楼", "测试西区"));

        skilledWorkerId = givenWorker("test-dispatch-skilled", "测试师傅甲", building1);
        otherWorkerId = givenWorker("test-dispatch-other", "测试师傅乙", null);
    }

    @Test
    void 四项明细齐全_缺数据的项记中性分() throws Exception {
        long ticketId = givenTicket(building1, waterCategoryId, null, 10);
        // 甲擅长水电；乙什么都没维护
        setSkills(skilledWorkerId, new long[]{waterCategoryId});

        JsonNode candidates = candidates(ticketId);
        JsonNode skilled = find(candidates, skilledWorkerId);
        JsonNode other = find(candidates, otherWorkerId);

        // 五项齐全（顺序固定：技能 / 满意度 / 负载 / 距离 / 值班）
        assertThat(skilled.path("items")).hasSize(5);
        assertThat(skilled.path("items").get(0).path("key").asText()).isEqualTo("skill");
        assertThat(skilled.path("items").get(3).path("key").asText()).isEqualTo("distance");
        assertThat(skilled.path("items").get(4).path("key").asText()).isEqualTo("shift");

        // 技能：甲擅长（1.0），乙未维护（中性 0.5 —— 缺数据不等于差）
        assertThat(item(skilled, "skill").path("score").asDouble()).isEqualTo(1.0);
        assertThat(item(skilled, "skill").path("detail").asText()).contains("擅长");
        assertThat(item(other, "skill").path("score").asDouble()).isEqualTo(0.5);
        assertThat(item(other, "skill").path("detail").asText()).isEqualTo("未维护擅长类别/工种");

        // 距离：甲负责该楼栋（1.0），乙没设负责楼栋（最差 0.2，且写明是数据没维护）
        assertThat(item(skilled, "distance").path("score").asDouble()).isEqualTo(1.0);
        assertThat(item(skilled, "distance").path("detail").asText()).isEqualTo("负责该楼栋");
        assertThat(item(other, "distance").path("score").asDouble()).isEqualTo(0.2);
        assertThat(item(other, "distance").path("detail").asText()).isEqualTo("未设置负责楼栋");

        // 满意度：都没评价 → 中性 0.5
        assertThat(item(skilled, "satisfaction").path("score").asDouble()).isEqualTo(0.5);
        assertThat(item(skilled, "satisfaction").path("detail").asText()).isEqualTo("暂无评价");

        // 权重与总分：权重来自配置（默认 0.4/0.3/0.2/0.1/0.1），总分落在 0~1
        assertThat(item(skilled, "skill").path("weight").asDouble()).isEqualTo(0.4);
        assertThat(item(skilled, "shift").path("weight").asDouble()).isEqualTo(0.1);
        // 没排班记录 → 中性 0.5，不是 0（缺数据不等于差）
        assertThat(item(skilled, "shift").path("score").asDouble()).isEqualTo(0.5);
        assertThat(skilled.path("score").asDouble()).isBetween(0.0, 1.0);
        assertThat(skilled.path("reason").asText()).isNotBlank().contains("擅长");

        // 甲应当排在乙前面
        assertThat(candidates.get(0).path("workerId").asLong()).isEqualTo(skilledWorkerId);
    }

    @Test
    void 负载与满意度都会影响排名() throws Exception {
        long ticketId = givenTicket(building1, waterCategoryId, null, 10);

        // 甲：1 张进行中的单（负载 0.8）+ 一条 5 分评价（满意度 1.0）
        long busyTicket = givenTicket(building1, waterCategoryId, skilledWorkerId, 30);
        givenEvaluation(busyTicket, 5);
        // 乙：0 张进行中，没有评价

        JsonNode candidates = candidates(ticketId);
        JsonNode skilled = find(candidates, skilledWorkerId);
        JsonNode other = find(candidates, otherWorkerId);

        assertThat(skilled.path("activeCount").asInt()).isEqualTo(1);
        assertThat(item(skilled, "load").path("score").asDouble()).isEqualTo(0.8);
        assertThat(item(skilled, "load").path("detail").asText()).isEqualTo("当前 1 单");
        assertThat(item(skilled, "satisfaction").path("score").asDouble()).isEqualTo(1.0);
        assertThat(item(skilled, "satisfaction").path("detail").asText()).contains("历史平均 5.0 分");

        assertThat(other.path("activeCount").asInt()).isZero();
        assertThat(item(other, "load").path("score").asDouble()).isEqualTo(1.0);
    }

    @Test
    void 同区与跨区的距离分不同() throws Exception {
        // 乙负责 3 号楼（西区）；工单在 1 号楼（东区）
        setBuildings(otherWorkerId, new long[]{buildingOtherArea});
        long ticketId = givenTicket(building1, waterCategoryId, null, 10);

        JsonNode other = find(candidates(ticketId), otherWorkerId);
        assertThat(item(other, "distance").path("score").asDouble()).isEqualTo(0.2);
        assertThat(item(other, "distance").path("detail").asText()).isEqualTo("跨区");

        // 乙改负责 2 号楼（与 1 号楼同属东区）
        setBuildings(otherWorkerId, new long[]{buildingSameArea});
        JsonNode sameArea = find(candidates(ticketId), otherWorkerId);
        assertThat(item(sameArea, "distance").path("score").asDouble()).isEqualTo(0.6);
        assertThat(item(sameArea, "distance").path("detail").asText()).contains("同区");
    }

    /** 候选只包含本租户启用中的师傅；别的租户的师傅不该出现在名单里。 */
    @Test
    void 候选只有本租户的启用师傅() throws Exception {
        givenWorker("test-dispatch-other-tenant", "别校师傅", null, 99L);
        long ticketId = givenTicket(building1, waterCategoryId, null, 10);

        JsonNode candidates = candidates(ticketId);
        for (JsonNode candidate : candidates) {
            assertThat(candidate.path("username").asText()).isNotEqualTo("test-dispatch-other-tenant");
        }
    }

    /** 只排序、不写库：调完接口，工单的状态 / 派单时间 / 维修工都没变。 */
    @Test
    void 候选接口不改工单() throws Exception {
        long ticketId = givenTicket(building1, waterCategoryId, null, 10);
        Ticket before = ticketMapper.selectById(ticketId);

        candidates(ticketId);

        Ticket after = ticketMapper.selectById(ticketId);
        assertThat(after.getStatus()).isEqualTo(before.getStatus());
        assertThat(after.getWorkerId()).isNull();
        assertThat(after.getDispatchTime()).isNull();
    }

    @Test
    void 学生不能看派单候选() throws Exception {
        long ticketId = givenTicket(building1, waterCategoryId, null, 10);
        mockMvc.perform(get("/api/admin/tickets/" + ticketId + "/dispatch-candidates")
                        .header("Authorization", "Bearer " + student))
                .andExpect(jsonPath("$.code").value(10003));
    }

    @Test
    void 设置擅长类别_列表能看到_非法类别被拒() throws Exception {
        setSkills(otherWorkerId, new long[]{waterCategoryId});

        JsonNode worker = findWorker("test-dispatch-other");
        assertThat(worker.path("skillCategoryIds").get(0).asLong()).isEqualTo(waterCategoryId);
        assertThat(worker.path("skillCategoryNames").get(0).asText()).isEqualTo("水电");

        // 不存在的类别 → 拒绝
        mockMvc.perform(put("/api/admin/workers/" + otherWorkerId + "/skills")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("categoryIds", java.util.List.of(99999999L)))))
                .andExpect(jsonPath("$.code").value(10001));

        // 空数组 = 清空
        setSkills(otherWorkerId, new long[]{});
        assertThat(findWorker("test-dispatch-other").path("skillCategoryIds")).isEmpty();
    }

    // ==================== 工具 ====================

    @Test
    void 值班维度影响排名_默认隐藏明确请假() throws Exception {
        long ticketId = givenTicket(building1, waterCategoryId, null, 10);
        long onDutyId = givenWorker("test-dispatch-onduty", "值班师傅", building1);
        long noRecordId = givenWorker("test-dispatch-norecord", "未排班师傅", building1);
        long offDutyId = givenWorker("test-dispatch-offduty", "请假师傅", building1);

        setShift(onDutyId, java.time.LocalDate.now(), 1, 1);
        setShift(offDutyId, java.time.LocalDate.now(), 1, 0);

        JsonNode candidates = candidates(ticketId);
        JsonNode onDuty = find(candidates, onDutyId);
        JsonNode noRecord = find(candidates, noRecordId);
        assertThat(item(onDuty, "shift").path("score").asDouble()).isEqualTo(1.0);
        assertThat(item(onDuty, "shift").path("detail").asText()).contains("今日在班");
        assertThat(onDuty.path("onDutyToday").asBoolean()).isTrue();
        assertThat(item(noRecord, "shift").path("score").asDouble()).isEqualTo(0.5);
        assertThat(noRecord.path("onDutyToday").isNull()).isTrue();
        assertThat(candidateIds(candidates)).doesNotContain(offDutyId);
        assertThat(onDuty.path("score").asDouble()).isGreaterThan(noRecord.path("score").asDouble());

        JsonNode withOffDuty = candidates(ticketId, true);
        JsonNode offDuty = find(withOffDuty, offDutyId);
        assertThat(item(offDuty, "shift").path("score").asDouble()).isEqualTo(0.0);
        assertThat(item(offDuty, "shift").path("detail").asText()).isEqualTo("今日请假/休息");
        assertThat(offDuty.path("onDutyToday").asBoolean()).isFalse();
    }

    @Test
    void 类别的默认工种匹配也算技能() throws Exception {
        long groupId = createGroup("测试派单工种");
        long otherGroupId = createGroup("测试派单其他工种");
        long categoryId = createCategory("测试工种类别", groupId);
        long ticketId = givenTicket(building1, categoryId, null, 10);

        long groupWorker = givenWorker("test-dispatch-group", "工种师傅", building1);
        long otherGroupWorker = givenWorker("test-dispatch-othergroup", "别组师傅", building1);
        long emptyWorker = givenWorker("test-dispatch-empty", "无数据师傅", building1);
        setGroupWorkers(groupId, java.util.List.of(groupWorker));
        setGroupWorkers(otherGroupId, java.util.List.of(otherGroupWorker));

        JsonNode candidates = candidates(ticketId);
        JsonNode matched = find(candidates, groupWorker);
        JsonNode mismatched = find(candidates, otherGroupWorker);
        JsonNode empty = find(candidates, emptyWorker);

        assertThat(item(matched, "skill").path("score").asDouble()).isEqualTo(1.0);
        assertThat(item(matched, "skill").path("detail").asText()).contains("对应工种");
        assertThat(item(mismatched, "skill").path("score").asDouble()).isEqualTo(0.0);
        assertThat(item(empty, "skill").path("score").asDouble()).isEqualTo(0.5);
    }

    private JsonNode candidates(long ticketId) throws Exception {
        return candidates(ticketId, false);
    }

    private JsonNode candidates(long ticketId, boolean includeOffDuty) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/admin/tickets/" + ticketId + "/dispatch-candidates")
                        .param("includeOffDuty", String.valueOf(includeOffDuty))
                        .header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        return json(result).path("data");
    }

    private java.util.List<Long> candidateIds(JsonNode candidates) {
        java.util.List<Long> ids = new java.util.ArrayList<>();
        candidates.forEach(candidate -> ids.add(candidate.path("workerId").asLong()));
        return ids;
    }

    private JsonNode find(JsonNode candidates, long workerId) {
        for (JsonNode candidate : candidates) {
            if (candidate.path("workerId").asLong() == workerId) {
                return candidate;
            }
        }
        throw new AssertionError("候选里没有 workerId=" + workerId);
    }

    private JsonNode item(JsonNode candidate, String key) {
        for (JsonNode item : candidate.path("items")) {
            if (key.equals(item.path("key").asText())) {
                return item;
            }
        }
        throw new AssertionError("候选人 " + candidate.path("username").asText() + " 没有 " + key + " 项");
    }

    private JsonNode findWorker(String username) throws Exception {
        MvcResult result = mockMvc.perform(get("/api/admin/workers")
                        .param("pageSize", "100")
                        .param("keyword", username)
                        .header("Authorization", "Bearer " + admin))
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        for (JsonNode worker : json(result).path("data").path("list")) {
            if (username.equals(worker.path("username").asText())) {
                return worker;
            }
        }
        throw new AssertionError("列表里没有维修工 " + username);
    }

    private void setSkills(long workerId, long[] categoryIds) throws Exception {
        java.util.List<Long> ids = new java.util.ArrayList<>();
        for (long id : categoryIds) {
            ids.add(id);
        }
        mockMvc.perform(put("/api/admin/workers/" + workerId + "/skills")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("categoryIds", ids))))
                .andExpect(jsonPath("$.code").value(0));
    }

    private void setBuildings(long workerId, long[] buildingIds) throws Exception {
        java.util.List<Long> ids = new java.util.ArrayList<>();
        for (long id : buildingIds) {
            ids.add(id);
        }
        mockMvc.perform(put("/api/admin/workers/" + workerId + "/buildings")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("buildingIds", ids))))
                .andExpect(jsonPath("$.code").value(0));
    }

    private void setShift(long workerId, java.time.LocalDate date, int shiftType, int status) throws Exception {
        mockMvc.perform(put("/api/admin/worker-shifts")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "workerId", workerId, "shiftDate", date.toString(),
                                "shiftType", shiftType, "status", status))))
                .andExpect(jsonPath("$.code").value(0));
    }

    private long createGroup(String name) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/admin/worker-groups")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", name, "sort", 0))))
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        return json(result).path("data").path("id").asLong();
    }

    private void setGroupWorkers(long groupId, java.util.List<Long> workerIds) throws Exception {
        mockMvc.perform(put("/api/admin/worker-groups/" + groupId + "/workers")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("workerIds", workerIds))))
                .andExpect(jsonPath("$.code").value(0));
    }

    private long createCategory(String name, long workerGroupId) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/admin/categories")
                        .header("Authorization", "Bearer " + admin)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "name", name, "defaultUrgency", 1, "sort", 0,
                                "workerGroupId", workerGroupId))))
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        return json(result).path("data").path("id").asLong();
    }

    private long givenWorker(String username, String realName, Long responsibleBuildingId) {
        return givenWorker(username, realName, responsibleBuildingId, TENANT);
    }

    private long givenWorker(String username, String realName, Long responsibleBuildingId, long tenantId) {
        SysUser worker = new SysUser();
        worker.setTenantId(tenantId);
        worker.setUsername(username);
        worker.setPassword(passwordEncoder.encode(PASSWORD));
        worker.setRealName(realName);
        worker.setUserType(2);
        worker.setStatus(1);
        sysUserMapper.insert(worker);

        SysUserRole link = new SysUserRole();
        link.setUserId(worker.getId());
        link.setRoleId(ROLE_WORKER);
        sysUserRoleMapper.insert(link);

        if (responsibleBuildingId != null) {
            WorkerBuilding responsibility = new WorkerBuilding();
            responsibility.setTenantId(tenantId);
            responsibility.setWorkerId(worker.getId());
            responsibility.setBuildingId(responsibleBuildingId);
            workerBuildingMapper.insert(responsibility);
        }
        return worker.getId();
    }

    private long insertBuilding(String name, String area) {
        Building building = new Building();
        building.setTenantId(TENANT);
        building.setName(name);
        building.setArea(area);
        building.setSort(99);
        building.setStatus(1);
        buildingMapper.insert(building);
        return building.getId();
    }

    private long givenTicket(long buildingId, long categoryId, Long workerId, int status) {
        Ticket ticket = new Ticket();
        ticket.setTenantId(TENANT);
        ticket.setTicketNo("WX-DISPATCH-" + System.nanoTime());
        ticket.setStudentId(studentId);
        ticket.setBuildingId(buildingId);
        ticket.setRoom("1-101");
        ticket.setCategoryId(categoryId);
        ticket.setUrgency(1);
        ticket.setStatus(status);
        ticket.setWorkerId(workerId);
        ticket.setDescription("测试派单评分");
        ticket.setSubmitTime(LocalDateTime.now());
        ticketMapper.insert(ticket);
        return ticket.getId();
    }

    private void givenEvaluation(long ticketId, int score) {
        TicketEvaluation evaluation = new TicketEvaluation();
        evaluation.setTenantId(TENANT);
        evaluation.setTicketId(ticketId);
        evaluation.setStudentId(studentId);
        evaluation.setScore(score);
        evaluation.setContent("测试评价");
        evaluation.setCreateTime(LocalDateTime.now());
        ticketEvaluationMapper.insert(evaluation);
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
