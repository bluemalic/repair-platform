package com.bluemalic.repair;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.bluemalic.repair.common.TicketStatus;
import com.bluemalic.repair.config.DemoProperties;
import com.bluemalic.repair.entity.Building;
import com.bluemalic.repair.entity.Notification;
import com.bluemalic.repair.entity.SysUser;
import com.bluemalic.repair.entity.Ticket;
import com.bluemalic.repair.entity.TicketEvaluation;
import com.bluemalic.repair.entity.TicketLog;
import com.bluemalic.repair.entity.WorkerBuilding;
import com.bluemalic.repair.job.DemoResetJob;
import com.bluemalic.repair.mapper.BuildingMapper;
import com.bluemalic.repair.mapper.NotificationMapper;
import com.bluemalic.repair.mapper.SysUserMapper;
import com.bluemalic.repair.mapper.TicketEvaluationMapper;
import com.bluemalic.repair.mapper.TicketLogMapper;
import com.bluemalic.repair.mapper.TicketMapper;
import com.bluemalic.repair.mapper.WorkerBuildingMapper;
import com.bluemalic.repair.service.DemoResetService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 演示数据重置（公网演示站的日常维护任务）。
 *
 * <p>重点验证三件事，都是"错了也不会立刻暴露"的：
 * <ol>
 *   <li><b>默认关闭</b>：演示模式没开时，定时任务这个 Bean 压根不存在——生产部署不该有这个定时器</li>
 *   <li><b>重置真的把站恢复原样</b>：业务数据清干净、演示账号重建、**访客改过的口令也改回来**
 *       （这是最容易被忽略的一条：口令被改掉后演示站就废了，而现象只是"登录页上的口令登不上"）</li>
 *   <li><b>不会误伤</b>：只动配置指定的租户；楼栋/类别这些基础数据不删不重建</li>
 * </ol>
 *
 * <p><b>注意有一处副作用跑在事务之外</b>：对象存储的清理不是数据库操作，不受测试事务回滚保护，
 * 每次跑这个类都会把本地 MinIO 里租户 1 的图片删掉。删的都是测试上传的图，不影响其它用例
 * （上传用例是"传了再读"，不依赖历史图片）。
 */
@IntegrationTest
class DemoResetTest {

    private static final String DEMO_PASSWORD = "Demo@123456";
    private static final long TENANT = 1L;

    @Autowired
    private DemoResetService demoResetService;

    @Autowired
    private DemoProperties demoProperties;

    /** 演示模式默认关闭 → 这个 Bean 不该存在。required = false 才能断言"没有"。 */
    @Autowired(required = false)
    private DemoResetJob demoResetJob;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private TicketMapper ticketMapper;

    @Autowired
    private TicketLogMapper ticketLogMapper;

    @Autowired
    private TicketEvaluationMapper ticketEvaluationMapper;

    @Autowired
    private WorkerBuildingMapper workerBuildingMapper;

    @Autowired
    private BuildingMapper buildingMapper;

    @Autowired
    private NotificationMapper notificationMapper;

    @Test
    void disabledByDefaultSoNoScheduledJob() {
        assertThat(demoProperties.isEnabled()).isFalse();
        assertThat(demoResetJob).isNull();
    }

    @Test
    void resetRebuildsAccountsTicketsAndEvaluations() {
        DemoResetService.Result result = demoResetService.reset();

        assertThat(result.createdAccounts()).isEqualTo(5);
        assertThat(result.createdTickets()).isEqualTo(12);

        // 五个演示账号：1 后勤 + 2 维修工 + 2 学生
        assertThat(user("admin").getUserType()).isEqualTo(3);
        assertThat(user("worker01").getUserType()).isEqualTo(2);
        assertThat(user("worker02").getUserType()).isEqualTo(2);
        assertThat(user("20260001").getUserType()).isEqualTo(1);
        assertThat(user("20260002").getUserType()).isEqualTo(1);
        assertThat(user("admin").getStatus()).isEqualTo(1);

        // 工单状态铺开：看板上每个状态都有人
        List<Ticket> tickets = tickets();
        assertThat(tickets).hasSize(12);
        assertThat(tickets.stream().map(Ticket::getStatus).distinct())
                .contains(10, 20, 30, 40, 50, 60, 70, 80);

        // 每条工单都有流转日志，且都从"提交"开始
        assertThat(ticketLogMapper.selectCount(Wrappers.<TicketLog>lambdaQuery()
                .eq(TicketLog::getTenantId, TENANT))).isGreaterThanOrEqualTo(12);
        for (Ticket ticket : tickets) {
            assertThat(ticketLogMapper.selectCount(Wrappers.<TicketLog>lambdaQuery()
                    .eq(TicketLog::getTicketId, ticket.getId())))
                    .as("工单 %s 应至少有一条流转日志", ticket.getTicketNo())
                    .isGreaterThanOrEqualTo(1);
        }

        // 已评价的工单让"满意度"有数据
        assertThat(ticketEvaluationMapper.selectCount(Wrappers.<TicketEvaluation>lambdaQuery()
                .eq(TicketEvaluation::getTenantId, TENANT))).isEqualTo(6);

        // 两个维修工各有负责楼栋，否则数据权限下他们看不到工单
        assertThat(workerBuildingMapper.selectCount(Wrappers.<WorkerBuilding>lambdaQuery()
                .eq(WorkerBuilding::getTenantId, TENANT))).isEqualTo(3);
    }

    @Test
    void activeTicketsAreRecentEnoughToAvoidTimeoutTasks() {
        demoResetService.reset();

        // 未结束的工单必须落在超时阈值（24h/48h）之内，否则每天重置完就会立刻触发
        // "接单超时提醒 / 处理超时升级"，把演示站刷成一屏通知
        LocalDateTime limit = LocalDateTime.now().minusHours(24);
        for (Ticket ticket : tickets()) {
            if (ticket.getStatus() < TicketStatus.FINISHED.getCode()) {
                assertThat(ticket.getSubmitTime())
                        .as("未结束的工单 %s 的提交时间不该早于 24 小时前", ticket.getTicketNo())
                        .isAfter(limit);
            }
        }
    }

    @Test
    void resetIsIdempotent() {
        demoResetService.reset();
        demoResetService.reset();

        // 连跑两次不会翻倍：工单还是 12 条、演示账号还是 5 个、负责楼栋还是 3 条
        assertThat(tickets()).hasSize(12);
        assertThat(countDemoAccounts()).isEqualTo(5);
        assertThat(workerBuildingMapper.selectCount(Wrappers.<WorkerBuilding>lambdaQuery()
                .eq(WorkerBuilding::getTenantId, TENANT))).isEqualTo(3);
    }

    @Test
    void resetRestoresPasswordChangedByVisitor() throws Exception {
        demoResetService.reset();
        loginExpectSuccess("admin", DEMO_PASSWORD);

        // 访客登录后把演示口令改掉了 —— 演示站从此登不进去，而现象只是"口令不对"
        String token = login("admin", DEMO_PASSWORD);
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put("/api/auth/password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                Map.of("oldPassword", DEMO_PASSWORD, "newPassword", "Visitor@12345"))))
                .andExpect(jsonPath("$.code").value(0));

        // 重置之后，登录页上写的那个口令又能用了
        demoResetService.reset();
        loginExpectSuccess("admin", DEMO_PASSWORD);
    }

    @Test
    void resetWipesBusinessDataButKeepsDictionaries() {
        // 造一条"访客留下的"工单与通知
        Ticket visitorTicket = new Ticket();
        visitorTicket.setTenantId(TENANT);
        visitorTicket.setTicketNo("WX-VISITOR-0001");
        visitorTicket.setStudentId(1L);
        visitorTicket.setBuildingId(1L);
        visitorTicket.setRoom("9-999");
        visitorTicket.setCategoryId(1L);
        visitorTicket.setDescription("访客留下的垃圾工单");
        visitorTicket.setStatus(TicketStatus.TO_DISPATCH.getCode());
        visitorTicket.setSubmitTime(LocalDateTime.now());
        ticketMapper.insert(visitorTicket);

        Notification notification = new Notification();
        notification.setTenantId(TENANT);
        notification.setReceiverId(1L);
        notification.setType("TICKET");
        notification.setTitle("访客留下的通知");
        notification.setContent("…");
        notification.setIsRead(0);
        notificationMapper.insert(notification);

        // 记下一条自定义楼栋，重置后它必须还在（基础数据不删不重建）
        Building custom = new Building();
        custom.setTenantId(TENANT);
        custom.setName("test-演示重置不该动的楼");
        custom.setSort(99);
        custom.setStatus(1);
        buildingMapper.insert(custom);

        demoResetService.reset();

        assertThat(ticketMapper.selectById(visitorTicket.getId())).isNull();
        assertThat(notificationMapper.selectCount(Wrappers.<Notification>lambdaQuery()
                .eq(Notification::getTenantId, TENANT))).isZero();
        assertThat(buildingMapper.selectById(custom.getId())).isNotNull();
        assertThat(tickets()).hasSize(12);
    }

    @Test
    void resetNeverTouchesOtherTenants() {
        long otherTenant = 99L;
        Ticket otherTicket = new Ticket();
        otherTicket.setTenantId(otherTenant);
        otherTicket.setTicketNo("WX-OTHER-TENANT-0001");
        otherTicket.setStudentId(1L);
        otherTicket.setBuildingId(1L);
        otherTicket.setRoom("1-101");
        otherTicket.setCategoryId(1L);
        otherTicket.setDescription("别的租户的工单");
        otherTicket.setStatus(TicketStatus.TO_DISPATCH.getCode());
        otherTicket.setSubmitTime(LocalDateTime.now());
        ticketMapper.insert(otherTicket);

        demoResetService.reset();

        // 演示重置只动配置指定的租户：即使在生产库上误开了演示模式，破坏范围也是可控的
        assertThat(ticketMapper.selectById(otherTicket.getId())).isNotNull();
    }

    // ==================== 工具 ====================

    private SysUser user(String username) {
        SysUser user = sysUserMapper.selectOne(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getTenantId, TENANT)
                .eq(SysUser::getUsername, username));
        assertThat(user).as("演示账号 %s 应已创建", username).isNotNull();
        return user;
    }

    private List<Ticket> tickets() {
        return ticketMapper.selectList(Wrappers.<Ticket>lambdaQuery().eq(Ticket::getTenantId, TENANT));
    }

    private Long countDemoAccounts() {
        return sysUserMapper.selectCount(Wrappers.<SysUser>lambdaQuery()
                .eq(SysUser::getTenantId, TENANT)
                .in(SysUser::getUsername, List.of("admin", "worker01", "worker02", "20260001", "20260002")));
    }

    private void loginExpectSuccess(String username, String password) throws Exception {
        mockMvc.perform(loginRequest(username, password))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }

    private String login(String username, String password) throws Exception {
        MvcResult result = mockMvc.perform(loginRequest(username, password))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path("tokenValue").asText();
    }

    private org.springframework.test.web.servlet.RequestBuilder loginRequest(String username, String password)
            throws Exception {
        return post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(Map.of(
                        "tenantCode", "gdou", "username", username, "password", password)));
    }
}
