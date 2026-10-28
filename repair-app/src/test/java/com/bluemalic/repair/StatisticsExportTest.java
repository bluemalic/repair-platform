package com.bluemalic.repair;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.ExcelReader;
import com.alibaba.excel.read.listener.PageReadListener;
import com.bluemalic.repair.entity.SysUser;
import com.bluemalic.repair.entity.SysUserRole;
import com.bluemalic.repair.entity.Tenant;
import com.bluemalic.repair.entity.Ticket;
import com.bluemalic.repair.entity.TicketEvaluation;
import com.bluemalic.repair.entity.TicketLog;
import com.bluemalic.repair.mapper.BuildingMapper;
import com.bluemalic.repair.mapper.SysUserMapper;
import com.bluemalic.repair.mapper.SysUserRoleMapper;
import com.bluemalic.repair.mapper.TenantMapper;
import com.bluemalic.repair.mapper.TicketCategoryMapper;
import com.bluemalic.repair.mapper.TicketEvaluationMapper;
import com.bluemalic.repair.mapper.TicketLogMapper;
import com.bluemalic.repair.mapper.TicketMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 统计报表导出（P1）测试。
 *
 * <p>要守住的**不是"能导出"**——能导出是最低要求，而且很容易在改坏之后仍然"能导出"。真正容易坏的是三件事：
 *
 * <ol>
 *   <li><b>导出的数与接口的数一致</b>：断言是拿 {@code /overview}、{@code /worker-workload}
 *       的返回值去比，而不是把期望数字写死在测试里。哪天有人把口径复制成第二份实现，这里会红。</li>
 *   <li><b>失败时不留半个文件</b>：时间范围超限必须还是普通 JSON 错误，且**不能带
 *       {@code Content-Disposition}**——带了它，浏览器会把错误 JSON 当 .xlsx 存下来，
 *       现象是"下载了个打不开的文件"。</li>
 *   <li><b>越权与跨租户</b>：学生导不了；租户 2 的管理员导不出租户 1 的数。</li>
 * </ol>
 *
 * <p>窗口固定在 2020-03（久远的空窗口），理由与 {@code StatisticsTest} 相同：本地库里可能残留演示数据，
 * 用"近 30 天"会让断言随环境变化。
 *
 * <p>读取用的是 EasyExcel 自己的读取端而不是 POI：读得回来本身就证明文件是合法 xlsx，
 * 而且不必再往 pom 里加一个只为测试存在的依赖。
 */
@IntegrationTest
class StatisticsExportTest {

    private static final String PASSWORD = "Test@123456";
    private static final long ROLE_ADMIN = 3L;
    private static final String RANGE = "/api/admin/statistics/export?start=2020-03-01&end=2020-03-31";

    private static final String SHEET_SUMMARY = "概况";
    private static final String SHEET_TREND = "报修量趋势";
    private static final String SHEET_DISTRIBUTION = "分布统计";
    private static final String SHEET_WORKLOAD = "师傅工作量";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private SysUserRoleMapper sysUserRoleMapper;

    @Autowired
    private TenantMapper tenantMapper;

    @Autowired
    private BuildingMapper buildingMapper;

    @Autowired
    private TicketCategoryMapper ticketCategoryMapper;

    @Autowired
    private TicketMapper ticketMapper;

    @Autowired
    private TicketLogMapper ticketLogMapper;

    @Autowired
    private TicketEvaluationMapper ticketEvaluationMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private long workerA;
    private long workerB;

    /**
     * 窗口 2020-03 的三张工单 + 租户 2 的一张（验证不会跨租户导出）：
     * <pre>
     * A: 03-05 水电/1号楼/普通 到场30 完工50 评价5  有 ACCEPT_TIMEOUT  → 师傅甲
     * B: 03-06 家具/2号楼/紧急 到场40 完工70 评价3  有 PROCESS_TIMEOUT → 师傅甲
     * C: 03-20 水电/1号楼/特急 到场50 完工90 评价4  无超时             → 师傅乙
     * </pre>
     */
    @BeforeEach
    void seedTickets() {
        workerA = givenWorker("测试导出师傅甲");
        workerB = givenWorker("测试导出师傅乙");

        Ticket a = ticket(1L, "2020-03-05", 30, 50, 1, 1, 1, workerA);
        givenEvaluation(a, 5);
        givenTimeoutLog(a, "ACCEPT_TIMEOUT");

        Ticket b = ticket(1L, "2020-03-06", 40, 70, 2, 2, 2, workerA);
        givenEvaluation(b, 3);
        givenTimeoutLog(b, "PROCESS_TIMEOUT");

        Ticket c = ticket(1L, "2020-03-20", 50, 90, 1, 1, 3, workerB);
        givenEvaluation(c, 4);

        givenTenantWithOneTicket();
    }

    @Test
    void exportWritesFourSheetsWhoseSummaryMatchesTheJsonApi() throws Exception {
        String admin = givenAdmin("gdou");
        JsonNode overview = getData(admin, "/api/admin/statistics/overview?start=2020-03-01&end=2020-03-31");

        MvcResult result = exportRequest(admin, RANGE);
        byte[] xlsx = result.getResponse().getContentAsByteArray();

        assertThat(result.getResponse().getContentType())
                .isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        // 长度能对上 = 响应是完整的 xlsx，不是被截断的一截
        assertThat(result.getResponse().getContentLength()).isEqualTo(xlsx.length);

        // 按**名字**读回来：读得到就说明四张表的表名与顺序都对
        List<Map<Integer, String>> summary = readSheet(xlsx, SHEET_SUMMARY);
        assertThat(summary.get(0)).containsEntry(0, "指标").containsEntry(1, "数值").containsEntry(2, "口径");
        // 概况里的数与接口返回的一致——这是"导出与页面同口径"的直接断言
        assertThat(intOf(rowOf(summary, "工单总量"), 1)).isEqualTo(overview.path("total").asInt());
        assertThat(intOf(rowOf(summary, "超时工单数"), 1)).isEqualTo(overview.path("timeoutCount").asInt());
        assertThat(doubleOf(rowOf(summary, "超时率（%）"), 1))
                .isEqualTo(overview.path("timeoutRate").asDouble());
        assertThat(rowOf(summary, "统计区间").get(1)).isEqualTo("2020-03-01 ~ 2020-03-31");
    }

    @Test
    void trendDistributionAndWorkloadSheetsMatchTheJsonApi() throws Exception {
        String admin = givenAdmin("gdou");
        JsonNode trendApi = getData(admin, "/api/admin/statistics/trend?start=2020-03-01&end=2020-03-31");
        JsonNode workloadApi = getData(admin, "/api/admin/statistics/worker-workload?start=2020-03-01&end=2020-03-31");

        byte[] xlsx = exportRequest(admin, RANGE).getResponse().getContentAsByteArray();

        // 趋势：表头 + 31 天，没有工单的日期也补 0（与趋势接口一致）
        List<Map<Integer, String>> trend = readSheet(xlsx, SHEET_TREND);
        assertThat(trend).hasSize(trendApi.size() + 1);
        assertThat(intOf(rowOf(trend, "2020-03-05"), 1)).isEqualTo(1);
        assertThat(intOf(rowOf(trend, "2020-03-06"), 1)).isEqualTo(1);
        assertThat(intOf(rowOf(trend, "2020-03-20"), 1)).isEqualTo(1);
        assertThat(intOf(rowOf(trend, "2020-03-07"), 1)).isZero();

        // 分布：三个维度平铺在一张表里，用「维度」列区分
        List<Map<Integer, String>> distribution = readSheet(xlsx, SHEET_DISTRIBUTION);
        assertThat(distribution).hasSize(1 + countOf(admin, "category") + countOf(admin, "building")
                + countOf(admin, "urgency"));
        assertThat(intOf(rowOf(distribution, "类别", categoryName(1L)), 2)).isEqualTo(2);
        assertThat(intOf(rowOf(distribution, "楼栋", buildingName(2L)), 2)).isEqualTo(1);
        assertThat(intOf(rowOf(distribution, "紧急度", "特急"), 2)).isEqualTo(1);

        // 师傅工作量：行数与接口一致，且数值列是**真数值**（Excel 里能排序、能求和）
        List<Map<Integer, String>> workload = readSheet(xlsx, SHEET_WORKLOAD);
        assertThat(workload).hasSize(workloadApi.size() + 1);
        Map<Integer, String> first = rowOf(workload, "测试导出师傅甲");
        assertThat(intOf(first, 1)).isEqualTo(2);      // 完工数
        assertThat(doubleOf(first, 2)).isEqualTo(60.0); // 平均处理时长 = (50+70)/2
        assertThat(intOf(first, 3)).isEqualTo(1);      // 处理超时次数
        assertThat(doubleOf(first, 4)).isEqualTo(50.0); // 按时完成率 = (2-1)/2
    }

    @Test
    void invalidRangeFailsAsJsonAndSetsNoAttachmentHeader() throws Exception {
        String admin = givenAdmin("gdou");

        // 跨度 5 年 > 366 天上限。取数发生在写第一个字节之前，所以这里必须还是一个**能看的 JSON 错误**
        MvcResult result = exportRequest(admin, "/api/admin/statistics/export?start=2020-01-01&end=2025-01-01");

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(body.path("code").asInt()).isEqualTo(10001);
        // 这两条是本次实现顺序（取数 → 设头 → 写出）的守卫：提前设了头，浏览器就会把这段 JSON
        // 当成 .xlsx 存下来，用户看到的是"下载了个打不开的文件"
        assertThat(result.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION)).isNull();
        assertThat(result.getResponse().getContentType()).contains("application/json");
    }

    @Test
    void exportSetsUtf8FileNameAndFallsBackToExportDate() throws Exception {
        String admin = givenAdmin("gdou");

        String attachment = exportRequest(admin, RANGE).getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION);
        assertThat(attachment).isNotNull();
        // 中文名走 RFC 5987（%E6%8A%A5 是「报」），ASCII 名兜底——只有前者带得动 UTF-8
        assertThat(attachment).contains("filename*=UTF-8''%E6%8A%A5%E4%BF%AE%E7%BB%9F%E8%AE%A1_2020-03-01_2020-03-31.xlsx");
        assertThat(attachment).contains("filename=\"statistics_2020-03-01_2020-03-31.xlsx\"");

        // 不传日期：文件名退回导出当天（真正生效的区间以工作簿里的「统计区间」为准）
        String noRange = exportRequest(admin, "/api/admin/statistics/export")
                .getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION);
        assertThat(noRange).contains("statistics_" + java.time.LocalDate.now() + ".xlsx");
    }

    @Test
    void exportRequiresViewPermission() throws Exception {
        String student = givenToken("test-exp-student", 1, 1L, "gdou");

        mockMvc.perform(get(RANGE).header("Authorization", "Bearer " + student))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(10003));
    }

    @Test
    void exportDoesNotBreakTheOpenApiDocument() throws Exception {
        // 这是全项目唯一返回 void 的接口（响应体是二进制，不走 Result<T>），而文档页是 springdoc
        // 扫注解生成的。它要是被这个接口弄坏了，现象是 /doc.html 整个打不开，而从接口本身完全看不出来。
        MvcResult result = mockMvc.perform(get("/v3/api-docs")).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(200);
        assertThat(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .contains("/api/admin/statistics/export");
    }

    @Test
    void exportOnlyContainsOwnTenantAndSurvivesEmptyRange() throws Exception {
        // 租户 2 的管理员：只看得到自己那 1 条，看不到租户 1 的 3 条
        String otherAdmin = givenAdmin("test-exp-t2");
        List<Map<Integer, String>> summary =
                readSheet(exportRequest(otherAdmin, RANGE).getResponse().getContentAsByteArray(), SHEET_SUMMARY);
        assertThat(intOf(rowOf(summary, "工单总量"), 1)).isEqualTo(1);

        // 空窗口：一张工单都没有时也要出得来——三张数据表只写表头，概况照样有数（0 而不是空）
        byte[] empty = exportRequest(otherAdmin, "/api/admin/statistics/export?start=2020-05-01&end=2020-05-31")
                .getResponse().getContentAsByteArray();
        assertThat(intOf(rowOf(readSheet(empty, SHEET_SUMMARY), "工单总量"), 1)).isZero();
        assertThat(readSheet(empty, SHEET_WORKLOAD)).hasSize(1);   // 只有表头
        assertThat(readSheet(empty, SHEET_DISTRIBUTION)).hasSize(1);
    }

    // ==================== 发请求 / 读工作簿 ====================

    private MvcResult exportRequest(String token, String url) throws Exception {
        return mockMvc.perform(get(url).header("Authorization", "Bearer " + token)).andReturn();
    }

    /**
     * 把导出的文件读回来。{@code headRowNumber(0)} 让表头也当成一行数据返回，
     * 这样才能断言"表头写的是不是这几个字"。
     */
    private List<Map<Integer, String>> readSheet(byte[] xlsx, String sheetName) throws Exception {
        List<Map<Integer, String>> rows = new ArrayList<>();
        try (InputStream in = new ByteArrayInputStream(xlsx);
             ExcelReader reader = EasyExcel.read(in, new PageReadListener<Map<Integer, String>>(rows::addAll))
                     .build()) {
            reader.read(EasyExcel.readSheet(sheetName).headRowNumber(0).build());
        }
        return rows;
    }

    /** 按第一列的值找行；找不到直接失败，比返回 null 之后在别处 NPE 好定位。 */
    private Map<Integer, String> rowOf(List<Map<Integer, String>> sheet, String firstColumn) {
        return sheet.stream()
                .filter(row -> firstColumn.equals(row.get(0)))
                .findFirst()
                .orElseThrow(() -> new AssertionError("表里没有第一列为「" + firstColumn + "」的行：" + sheet));
    }

    /** 两个键都要对上（分布表的第一列是维度、第二列才是名称）。 */
    private Map<Integer, String> rowOf(List<Map<Integer, String>> sheet, String firstColumn, String secondColumn) {
        return sheet.stream()
                .filter(row -> firstColumn.equals(row.get(0)) && secondColumn.equals(row.get(1)))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "表里没有「" + firstColumn + " / " + secondColumn + "」这一行：" + sheet));
    }

    private int countOf(String token, String dimension) throws Exception {
        return getData(token, "/api/admin/statistics/distribution?start=2020-03-01&end=2020-03-31&dimension=" + dimension)
                .size();
    }

    /** 单元格回来的是字符串（可能 "2"，也可能 "2.0"），统一按数值比，不猜格式。 */
    private static int intOf(Map<Integer, String> row, int column) {
        return (int) doubleOf(row, column);
    }

    private static double doubleOf(Map<Integer, String> row, int column) {
        return Double.parseDouble(row.get(column).trim());
    }

    // ==================== 造数据（与 StatisticsTest 同一套路） ====================

    private Ticket ticket(Long tenantId, String submitDate, Integer arriveMinutes, Integer handleMinutes,
                          long categoryId, long buildingId, int urgency, Long workerId) {
        LocalDateTime submitAt = LocalDateTime.parse(submitDate + "T09:00:00");
        Ticket ticket = new Ticket();
        ticket.setTenantId(tenantId);
        ticket.setTicketNo("ST" + System.nanoTime());
        ticket.setStudentId(930000L);
        ticket.setWorkerId(workerId);
        ticket.setBuildingId(buildingId);
        ticket.setRoom("1-101");
        ticket.setCategoryId(categoryId);
        ticket.setUrgency(urgency);
        ticket.setStatus(handleMinutes == null ? 20 : 50);
        ticket.setSubmitTime(submitAt);
        ticket.setArriveMinutes(arriveMinutes);
        ticket.setHandleMinutes(handleMinutes);
        if (workerId != null) {
            ticket.setDispatchTime(submitAt.plusMinutes(5));
        }
        if (handleMinutes != null) {
            ticket.setFinishTime(submitAt.plusHours(2));
        }
        ticketMapper.insert(ticket);
        return ticket;
    }

    private void givenEvaluation(Ticket ticket, int score) {
        TicketEvaluation evaluation = new TicketEvaluation();
        evaluation.setTenantId(ticket.getTenantId());
        evaluation.setTicketId(ticket.getId());
        evaluation.setStudentId(ticket.getStudentId());
        evaluation.setScore(score);
        evaluation.setContent("导出测试");
        ticketEvaluationMapper.insert(evaluation);
    }

    private void givenTimeoutLog(Ticket ticket, String action) {
        TicketLog log = new TicketLog();
        log.setTenantId(ticket.getTenantId());
        log.setTicketId(ticket.getId());
        log.setFromStatus(ticket.getStatus());
        log.setToStatus(ticket.getStatus());
        log.setAction(action);
        log.setOperatorId(0L);
        ticketLogMapper.insert(log);
    }

    private long givenWorker(String realName) {
        SysUser worker = new SysUser();
        worker.setTenantId(1L);
        worker.setUsername("test-export-worker-" + UUID.randomUUID().toString().substring(0, 8));
        worker.setPassword(passwordEncoder.encode(PASSWORD));
        worker.setRealName(realName);
        worker.setUserType(2);
        worker.setStatus(1);
        sysUserMapper.insert(worker);
        return worker.getId();
    }

    /** 租户 2 + 一条工单：验证导出按租户隔离。 */
    private void givenTenantWithOneTicket() {
        Tenant tenant = new Tenant();
        // 编码与账号名都取短的：sys_user.username 是 varchar(32)，
        // 而登录名是按「test-exp- + 租户编码」拼出来的，长编码会直接撞上截断报错
        tenant.setCode("test-exp-t2");
        tenant.setName("导出测试租户");
        tenant.setStatus(1);
        tenantMapper.insert(tenant);
        ticket(tenant.getId(), "2020-03-10", 10, 20, 1, 1, 1, null);
    }

    private String givenAdmin(String tenantCode) throws Exception {
        return givenToken("test-exp-" + tenantCode, 3, ROLE_ADMIN, tenantCode);
    }

    private String givenToken(String username, int userType, long roleId, String tenantCode) throws Exception {
        Tenant tenant = tenantMapper.selectOne(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<Tenant>lambdaQuery()
                        .eq(Tenant::getCode, tenantCode));
        assertThat(tenant).as("租户 %s 应已存在", tenantCode).isNotNull();

        SysUser user = new SysUser();
        user.setTenantId(tenant.getId());
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
                                "tenantCode", tenantCode, "username", username, "password", PASSWORD))))
                .andExpect(jsonPath("$.code").value(0)).andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path("tokenValue").asText();
    }

    private JsonNode getData(String token, String url) throws Exception {
        MvcResult result = mockMvc.perform(get(url).header("Authorization", "Bearer " + token)).andReturn();
        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertThat(body.path("code").asInt()).as("接口应返回成功：" + url + " → " + body).isZero();
        return body.path("data");
    }

    private String buildingName(long buildingId) {
        return buildingMapper.selectById(buildingId).getName();
    }

    private String categoryName(long categoryId) {
        return ticketCategoryMapper.selectById(categoryId).getName();
    }
}
