package com.bluemalic.repair;

import com.bluemalic.repair.common.BizException;
import com.bluemalic.repair.common.UserType;
import com.bluemalic.repair.entity.AuditLog;
import com.bluemalic.repair.entity.Building;
import com.bluemalic.repair.entity.Notification;
import com.bluemalic.repair.entity.SysUser;
import com.bluemalic.repair.entity.SysUserRole;
import com.bluemalic.repair.entity.Tenant;
import com.bluemalic.repair.entity.Ticket;
import com.bluemalic.repair.entity.TicketCategory;
import com.bluemalic.repair.common.TicketStatus;
import com.bluemalic.repair.mapper.AuditLogMapper;
import com.bluemalic.repair.mapper.BuildingMapper;
import com.bluemalic.repair.mapper.NotificationMapper;
import com.bluemalic.repair.mapper.SysUserMapper;
import com.bluemalic.repair.mapper.SysUserRoleMapper;
import com.bluemalic.repair.mapper.TenantMapper;
import com.bluemalic.repair.mapper.TicketCategoryMapper;
import com.bluemalic.repair.mapper.TicketMapper;
import com.bluemalic.repair.service.TenantDatabaseService;
import com.bluemalic.repair.tenancy.TenantDataMigrator;
import com.bluemalic.repair.tenancy.TenantRoutingContext;
import com.bluemalic.repair.vo.TenantDatabaseVO;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 存量租户的**数据搬迁**（阶段 6 第 2 步 · ADR-021）。
 *
 * <p>这类工具最怕的不是"搬不动"，而是"搬漏了/搬串了还不吭声"。所以这里盯的是四件事：
 * <ol>
 *   <li><b>计划不漏表</b>：搬运计划里必须正好是"平台库的全部表 − 三张平台专属表"，
 *       新加一张业务表忘了加进计划 → 它就会永远留在平台库，切换后那所学校的这张表是空的；</li>
 *   <li><b>搬完必须一致</b>：逐表比对行数与全列校验和（{@code ticket} 那张表要能发现"源库又多了一行"）；</li>
 *   <li><b>不一致不许切</b>：这是唯一改变行为的一步，源库在搬完之后又被写过就必须拒绝，
 *       并说清是哪张表——"切换前再校验一次"就是这个测试第 ③ 步守住的；</li>
 *   <li><b>切换后真的走新库</b>：用"新建一个类别"来分辨——它只该出现在租户库里，
 *       而平台库那份旧数据必须原样留着（ADR-021：确认后再归档）。</li>
 * </ol>
 *
 * <p><b>为什么不在测试事务里</b>：搬迁是跨库的显式流程（建库、{@code INSERT … SELECT} 两库一句），
 * 塞进测试事务既跑不通（见 {@code TenantProvisionTest} 的说明），也看不到"提交之后才可见"的真实形态。
 */
@IntegrationTest
class TenantDataMigrationTest {

    private static final String PASSWORD = "Test@123456";
    private static final long ROLE_ADMIN = 3L;
    private static final long ROLE_PLATFORM = 4L;
    private static final long ROLE_STUDENT = 1L;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TenantDatabaseService tenantDatabaseService;

    @Autowired
    private TenantDataMigrator migrator;

    @Autowired
    private TenantMapper tenantMapper;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private SysUserRoleMapper sysUserRoleMapper;

    @Autowired
    private TicketMapper ticketMapper;

    @Autowired
    private TicketCategoryMapper categoryMapper;

    @Autowired
    private BuildingMapper buildingMapper;

    @Autowired
    private NotificationMapper notificationMapper;

    @Autowired
    private AuditLogMapper auditLogMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TenantTestCleanup cleanup;

    /** 平台库主池：造存量租户、以及"平台库视角"的断言都用它（不经过路由） */
    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    private final List<String> createdCodes = new ArrayList<>();

    // ==================== 计划不漏表 ====================

    @Test
    void 搬运计划正好覆盖平台库里那些租户侧的表() throws Exception {
        Set<String> planned = new HashSet<>(migrator.plannedTables());
        Set<String> inPlatformDb = new HashSet<>();
        try (Connection connection = controlDataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "SELECT TABLE_NAME FROM information_schema.TABLES"
                             + " WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = 'BASE TABLE'")) {
            statement.setString(1, connection.getCatalog());
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    inPlatformDb.add(rs.getString(1));
                }
            }
        }
        // 三张平台专属的表不搬：学校名录（只在平台库）、Flyway 历史、权限点（平台定义，两侧由种子保证）
        inPlatformDb.removeAll(Set.of("tenant", "flyway_schema_history", "sys_permission"));

        assertThat(planned)
                .as("搬运计划与平台库里的租户侧表必须一致：新加一张表时要同步改 TenantDataMigrator.PLAN，"
                        + "否则它会永远留在平台库（切换后那所学校的这张表是空的）")
                .isEqualTo(inPlatformDb);
    }

    // ==================== 搬家 → 校验 → 切换（端到端） ====================

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 搬完校验一致才能切换_切换后数据来自它自己的库() throws Exception {
        String code = "test-mig-" + (System.nanoTime() % 100000);
        createdCodes.add(code);
        long tenantId = seedLegacyTenant(code);
        long studentId = userIdOf(tenantId, "test-mig-student");
        // 搬家与切换都走**平台接口**（生产里正是平台运营在调），顺带把审计那条链路也跑到
        String platform = givenPlatformToken("test-mig-platform");

        // ① 还没搬：数据在平台库，目标库不存在
        TenantDatabaseVO before = tenantDatabaseService.status(tenantId);
        assertThat(before.getMode()).isEqualTo("SHARED");
        assertThat(before.getTargetReady()).isFalse();
        assertThat(before.getDbSchema()).isNull();

        // ② 搬家：16 张表逐表校验都要一致
        JsonNode copied = dataOf(platformPost("/api/platform/tenants/" + tenantId + "/database/copy", platform));
        assertThat(copied.path("targetReady").asBoolean()).isTrue();
        assertThat(copied.path("tables").size()).as("逐表校验的粒度是表")
                .isEqualTo(migrator.plannedTables().size());
        assertThat(copied.path("consistent").asBoolean()).as("刚搬完就该一致").isTrue();
        assertThat(copied.path("sourceRows").asLong()).as("至少搬过来账号与工单").isPositive();
        assertThat(copied.path("targetRows").asLong()).isEqualTo(copied.path("sourceRows").asLong());

        // ③ 源库在搬完之后又被写过：校验必须发现，而且**不许切换**（这正是"切换前再校验一次"挡的东西）
        givenTicket(tenantId, studentId, "搬家之后又报的一单");
        TenantDatabaseVO drifted = tenantDatabaseService.status(tenantId);
        assertThat(drifted.getConsistent()).isFalse();
        assertThat(inconsistentTables(drifted)).contains("ticket");
        JsonNode refused = platformPost("/api/platform/tenants/" + tenantId + "/database/switch", platform);
        assertThat(refused.path("code").asInt()).as("不一致时切换必须被拒").isEqualTo(10001);
        assertThat(refused.path("message").asText()).contains("不一致").contains("ticket");

        // ④ 重跑一次搬家（幂等）→ 一致 → 切换
        JsonNode again = dataOf(platformPost("/api/platform/tenants/" + tenantId + "/database/copy", platform));
        assertThat(again.path("consistent").asBoolean()).isTrue();
        JsonNode switched = dataOf(platformPost("/api/platform/tenants/" + tenantId + "/database/switch", platform));
        assertThat(switched.path("mode").asText()).isEqualTo("DEDICATED");
        String schema = switched.path("dbSchema").asText();
        assertThat(schema).as("库名 = 前缀 + 编码（连字符换成下划线）")
                .isEqualTo("repair_" + code.replace('-', '_'));
        assertThat(schemaOf(tenantId)).isEqualTo(schema);

        // ⑤ 切换后：数据来自它自己的库。用"新建一个类别"分辨——
        //    它只该落在租户库里；平台库那份是搬走那一刻的快照，原样留着（ADR-021：确认后再归档）
        String token = login(code, "test-mig-admin");
        mockMvc.perform(post("/api/admin/categories")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("name", "切换后新建的类别"))))
                .andExpect(jsonPath("$.code").value(0));
        assertThat(countIn("SELECT COUNT(*) FROM `" + schema + "`.ticket_category"
                + " WHERE name = '切换后新建的类别'")).as("写入必须落在租户库").isEqualTo(1);
        assertThat(countIn("SELECT COUNT(*) FROM ticket_category WHERE name = '切换后新建的类别'"))
                .as("平台库不该出现切换后的新数据").isZero();

        // 它自己库里的工单数是 2（种子的 1 张 + 第 ③ 步补的 1 张）
        mockMvc.perform(get("/api/admin/tickets").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.total").value(2));

        // 平台库那份旧数据仍在：这是"确认之前不删"的凭据，也是回退的依据
        assertThat(countIn("SELECT COUNT(*) FROM ticket WHERE tenant_id = " + tenantId))
                .as("平台库那份旧数据仍在（确认后再归档）").isEqualTo(2);

        // ⑥ 已经在它自己库里的学校：搬不了、也切不了（要先操作 db_schema 才能重做，见 docs/05 §5.4）
        JsonNode copyAgain = platformPost("/api/platform/tenants/" + tenantId + "/database/copy", platform);
        assertThat(copyAgain.path("code").asInt()).isEqualTo(10001);
        assertThat(copyAgain.path("message").asText()).contains("已经在它自己的库里");
        JsonNode switchAgain = platformPost("/api/platform/tenants/" + tenantId + "/database/switch", platform);
        assertThat(switchAgain.path("code").asInt()).isEqualTo(10001);
    }

    // ==================== 接口调用 ====================

    /** 平台运营账号 + token（与 TenantProvisionTest 同一套造法：平台账号没有创建接口） */
    private String givenPlatformToken(String username) throws Exception {
        TenantRoutingContext.callControl(() -> {
            SysUser user = new SysUser();
            user.setTenantId(0L);
            user.setUsername(username);
            user.setPassword(passwordEncoder.encode(PASSWORD));
            user.setRealName(username);
            user.setUserType(UserType.PLATFORM.getCode());
            user.setStatus(1);
            user.setMustChangePassword(0);
            sysUserMapper.insert(user);

            SysUserRole link = new SysUserRole();
            link.setUserId(user.getId());
            link.setRoleId(ROLE_PLATFORM);
            sysUserRoleMapper.insert(link);
            return null;
        });
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "tenantCode", "platform", "username", username, "password", PASSWORD))))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path("tokenValue").asText();
    }

    private JsonNode platformPost(String url, String token) throws Exception {
        MvcResult result = mockMvc.perform(post(url).header("Authorization", "Bearer " + token))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
    }

    /** 取出成功的返回体的 data 部分（code 非 0 直接失败，失败信息带 message） */
    private JsonNode dataOf(JsonNode response) {
        assertThat(response.path("code").asInt())
                .as("接口应当成功：%s", response.path("message").asText())
                .isZero();
        return response.path("data");
    }

    // ==================== 造数据 ====================

    /**
     * 造一所**存量形态**的学校：`db_schema` 为空、数据直接插平台库——就是"还没搬家的学校"的样子。
     * 数据铺开几张有代表性的表（账号 / 类别 / 楼栋 / 工单 / 通知 / 审计），让校验有东西可比。
     */
    private long seedLegacyTenant(String code) {
        return TenantRoutingContext.callControl(() -> {
            Tenant tenant = new Tenant();
            tenant.setName("搬迁测试学校-" + code);
            tenant.setCode(code);
            tenant.setStatus(1);
            tenant.setMaxStudents(200);
            tenant.setMaxWorkers(20);
            tenantMapper.insert(tenant);

            long tenantId = tenant.getId();
            long adminId = givenAccount(tenantId, "test-mig-admin", UserType.ADMIN.getCode(), ROLE_ADMIN);
            long studentId = givenAccount(tenantId, "test-mig-student", UserType.STUDENT.getCode(), ROLE_STUDENT);

            TicketCategory category = new TicketCategory();
            category.setTenantId(tenantId);
            category.setName("搬迁测试类别");
            category.setDefaultUrgency(1);
            category.setSort(1);
            category.setStatus(1);
            categoryMapper.insert(category);

            Building building = new Building();
            building.setTenantId(tenantId);
            building.setName("搬迁测试楼");
            building.setSort(1);
            building.setStatus(1);
            buildingMapper.insert(building);

            Notification notification = new Notification();
            notification.setTenantId(tenantId);
            notification.setReceiverId(studentId);
            notification.setType("TICKET_DISPATCHED");
            notification.setTitle("搬迁测试通知");
            notification.setIsRead(0);
            notificationMapper.insert(notification);

            AuditLog audit = new AuditLog();
            audit.setTenantId(tenantId);
            audit.setOperatorId(adminId);
            audit.setOperatorName("test-mig-admin");
            audit.setAction("STUDENT_CREATE");
            audit.setTargetType("STUDENT");
            audit.setTargetName("test-mig-student");
            audit.setCreateTime(LocalDateTime.now());
            auditLogMapper.insert(audit);

            seedTicket(tenantId, studentId, category.getId(), building.getId(), "搬迁测试的一单");
            return tenantId;
        });
    }

    private long givenAccount(long tenantId, String username, int userType, long roleId) {
        SysUser user = new SysUser();
        user.setTenantId(tenantId);
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setRealName(username);
        user.setUserType(userType);
        user.setStatus(1);
        user.setMustChangePassword(0);
        sysUserMapper.insert(user);

        SysUserRole link = new SysUserRole();
        link.setUserId(user.getId());
        link.setRoleId(roleId);
        sysUserRoleMapper.insert(link);
        return user.getId();
    }

    /** 直接给这个租户补一张工单（模拟"源库又被写过"）。 */
    private void givenTicket(long tenantId, long studentId, String room) {
        TenantRoutingContext.callControl(() -> {
            seedTicket(tenantId, studentId, categoryIdOf(tenantId), buildingIdOf(tenantId), room);
            return null;
        });
    }

    private void seedTicket(long tenantId, long studentId, long categoryId, long buildingId, String room) {
        Ticket ticket = new Ticket();
        ticket.setTenantId(tenantId);
        ticket.setTicketNo("WX-MIG-" + System.nanoTime());
        ticket.setStudentId(studentId);
        ticket.setBuildingId(buildingId);
        ticket.setRoom(room);
        ticket.setCategoryId(categoryId);
        ticket.setDescription("搬迁测试工单");
        ticket.setStatus(TicketStatus.TO_DISPATCH.getCode());
        ticket.setSubmitTime(LocalDateTime.now());
        ticketMapper.insert(ticket);
    }

    private long categoryIdOf(long tenantId) {
        return categoryMapper.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers
                        .<TicketCategory>lambdaQuery().eq(TicketCategory::getTenantId, tenantId))
                .get(0).getId();
    }

    private long buildingIdOf(long tenantId) {
        return buildingMapper.selectList(com.baomidou.mybatisplus.core.toolkit.Wrappers
                        .<Building>lambdaQuery().eq(Building::getTenantId, tenantId))
                .get(0).getId();
    }

    private long userIdOf(long tenantId, String username) {
        SysUser user = TenantRoutingContext.callControl(() -> sysUserMapper.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<SysUser>lambdaQuery()
                        .eq(SysUser::getTenantId, tenantId).eq(SysUser::getUsername, username)));
        assertThat(user).as("账号 %s 应当存在", username).isNotNull();
        return user.getId();
    }

    private String schemaOf(long tenantId) {
        return TenantRoutingContext.callControl(() -> tenantMapper.selectById(tenantId)).getDbSchema();
    }

    private String login(String tenantCode, String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "tenantCode", tenantCode, "username", username, "password", PASSWORD))))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path("tokenValue").asText();
    }

    /**
     * 数一下（走平台库主池直连，不经过路由）。
     *
     * <p>**库名要写进 SQL**（`\`库名\`.表`）：连接默认落在平台库上，不写库名的查询永远查平台库——
     * 那样"数据落在租户库里"这条断言会变成"平台库里没有"，**假绿**。
     */
    private int countIn(String sql) throws Exception {
        try (Connection connection = controlDataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    private List<String> inconsistentTables(TenantDatabaseVO vo) {
        return vo.getTables().stream()
                .filter(table -> !Boolean.TRUE.equals(table.getConsistent()))
                .map(com.bluemalic.repair.vo.TenantDatabaseTableVO::getTable)
                .toList();
    }

    private String inconsistent(TenantDatabaseVO vo) {
        return inconsistentTables(vo).toString();
    }

    @AfterEach
    void cleanUp() {
        cleanup.dropTenants(createdCodes);
        cleanup.dropAccounts("test-mig-");
        createdCodes.clear();
    }
}
