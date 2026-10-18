package com.bluemalic.repair;

import com.bluemalic.repair.common.UserType;
import com.bluemalic.repair.entity.SysUser;
import com.bluemalic.repair.entity.SysUserRole;
import com.bluemalic.repair.entity.Tenant;
import com.bluemalic.repair.entity.Ticket;
import com.bluemalic.repair.mapper.SysUserMapper;
import com.bluemalic.repair.mapper.SysUserRoleMapper;
import com.bluemalic.repair.mapper.TenantMapper;
import com.bluemalic.repair.mapper.TicketMapper;
import com.bluemalic.repair.service.DueTicket;
import com.bluemalic.repair.service.TimeoutService;
import com.bluemalic.repair.service.impl.TicketTimeoutHandler;
import com.bluemalic.repair.tenancy.TenantDatabaseProvisioner;
import com.bluemalic.repair.tenancy.TenantRoutingContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 定时任务按租户遍历（阶段 6 第 3 步 · ADR-021）：**已经搬家的学校，超时升级落进它自己的库**。
 *
 * <p>这条是"每租户一个库"里最容易漏的一块：超时调度没有登录态，它扫库、写日志、发通知
 * 全靠"当前该用哪个库"。第 3 步之前它只看平台库——搬到独立库的学校会**静默**失去超时升级
 * （不报错、只是永远不升级），所以这里用一所"已搬家"的学校端到端验一遍：
 *
 * <ol>
 *   <li>兜底扫描能从**它自己的库**里扫到那张超期工单（平台库里没有它）；</li>
 *   <li>队列路径消费出来的任务带着正确的租户，处理时日志与通知都落在那所学校自己的库里；</li>
 *   <li>平台库里查不到这条工单的任何日志与通知（没有串库）。</li>
 * </ol>
 *
 * <p>用例不在测试事务里（要建库、要在另一个库里造数据），跑完由 {@link TenantTestCleanup} 收拾。
 */
@IntegrationTest
class TimeoutTenantRoutingTest {

    private static final String PASSWORD = "Test@123456";
    private static final long ROLE_ADMIN = 3L;

    @Autowired
    private TimeoutService timeoutService;

    @Autowired
    private TicketTimeoutHandler timeoutHandler;

    @Autowired
    private TenantDatabaseProvisioner provisioner;

    @Autowired
    private TenantMapper tenantMapper;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private SysUserRoleMapper sysUserRoleMapper;

    @Autowired
    private TicketMapper ticketMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TenantTestCleanup cleanup;

    /** 平台库主池：断言"平台库里没有"，以及清理 */
    @Autowired
    @Qualifier("controlDataSource")
    private DataSource controlDataSource;

    private final List<String> createdCodes = new ArrayList<>();

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void 已搬家学校的超时升级只写它自己的库() {
        String code = "test-tmo-" + (System.nanoTime() % 100000);
        createdCodes.add(code);
        String schema = provisioner.schemaFor(code);
        // 建库 + 跑租户库迁移（幂等）：这就是"搬家"之后的形态
        provisioner.createAndMigrate(schema);

        long tenantId = TenantRoutingContext.callControl(() -> {
            Tenant tenant = new Tenant();
            tenant.setName("超时测试学校-" + code);
            tenant.setCode(code);
            tenant.setDbSchema(schema);
            tenant.setStatus(1);
            tenant.setMaxStudents(200);
            tenant.setMaxWorkers(20);
            tenantMapper.insert(tenant);
            return tenant.getId();
        });

        // 它自己的库：一个管理员（超时升级的收件人）+ 一张 49 小时前派单、仍停在 30 处理中的工单
        // （进程阈值默认 2880 分钟 = 48h，所以 49h 足够触发兜底扫描）
        LocalDateTime now = LocalDateTime.now();
        long ticketId = TenantRoutingContext.call(schema, () -> {
            SysUser admin = new SysUser();
            admin.setTenantId(tenantId);
            admin.setUsername("test-tmo-admin");
            admin.setPassword(passwordEncoder.encode(PASSWORD));
            admin.setRealName("超时管理员");
            admin.setUserType(UserType.ADMIN.getCode());
            admin.setStatus(1);
            sysUserMapper.insert(admin);
            SysUserRole link = new SysUserRole();
            link.setUserId(admin.getId());
            link.setRoleId(ROLE_ADMIN);   // 平台内置角色，租户库里的种子保证了它存在
            sysUserRoleMapper.insert(link);

            Ticket ticket = new Ticket();
            ticket.setTenantId(tenantId);
            ticket.setTicketNo("WX-TMO-" + System.nanoTime());
            ticket.setStudentId(900001L);
            ticket.setWorkerId(900002L);
            ticket.setBuildingId(1L);
            ticket.setRoom("1-101");
            ticket.setCategoryId(1L);
            ticket.setUrgency(1);
            ticket.setStatus(30);
            ticket.setSubmitTime(now.minusHours(50));
            ticket.setDispatchTime(now.minusHours(49));
            ticket.setAcceptTime(now.minusHours(48));
            ticketMapper.insert(ticket);
            return ticket.getId();
        });

        // ① 兜底扫描要扫到"别的库"里的超期工单，并带着正确的租户
        List<DueTicket> scanned = timeoutService.backstopScanProcess().stream()
                .filter(task -> task.ticketId() == ticketId)
                .toList();
        assertThat(scanned).as("兜底扫描必须遍历到已搬家的学校").containsExactly(new DueTicket(tenantId, ticketId));

        // ② 队列路径：登记（带租户）→ 消费 → 处理
        timeoutService.registerProcessDeadline(ticketId, tenantId, Instant.now().minusSeconds(60));
        List<DueTicket> due = timeoutService.handleDueProcess().stream()
                .filter(task -> task.ticketId() == ticketId)
                .toList();
        assertThat(due).as("成员里要带着租户").containsExactly(new DueTicket(tenantId, ticketId));
        due.forEach(timeoutHandler::escalateProcessTimeout);

        // ③ 写入落在它自己的库：升级日志 + 给本校管理员的通知
        assertThat(countIn("SELECT COUNT(*) FROM `" + schema + "`.ticket_log"
                + " WHERE ticket_id = " + ticketId + " AND action = 'PROCESS_TIMEOUT'"))
                .as("升级日志必须写在租户库").isEqualTo(1);
        assertThat(countIn("SELECT COUNT(*) FROM `" + schema + "`.notification"
                + " WHERE ticket_id = " + ticketId + " AND type = 'TICKET_PROCESS_TIMEOUT'"))
                .as("升级通知必须送给这所学校自己的管理员").isEqualTo(1);

        // ④ 平台库里没有这条工单的任何痕迹（没有串库）
        assertThat(countIn("SELECT COUNT(*) FROM ticket_log WHERE ticket_id = " + ticketId))
                .as("平台库不该出现这所学校的日志").isZero();
        assertThat(countIn("SELECT COUNT(*) FROM notification WHERE ticket_id = " + ticketId))
                .as("平台库不该出现这所学校的通知").isZero();
    }

    // ==================== 工具 ====================

    /**
     * 数一下（平台库主池直连，不经过路由）。
     *
     * <p>**库名要写进 SQL**：连接默认落在平台库上，不写库名的查询永远查平台库——
     * 那样"写入落在租户库"的断言会变成假绿。
     */
    private int countIn(String sql) {
        try (Connection connection = controlDataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rs = statement.executeQuery()) {
            return rs.next() ? rs.getInt(1) : 0;
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @AfterEach
    void cleanUp() {
        cleanup.dropTenants(createdCodes);
        createdCodes.clear();
    }
}
