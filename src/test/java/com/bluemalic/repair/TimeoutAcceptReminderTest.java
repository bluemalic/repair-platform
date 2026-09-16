package com.bluemalic.repair;

import com.bluemalic.repair.common.TicketAction;
import com.bluemalic.repair.entity.Notification;
import com.bluemalic.repair.entity.SysUser;
import com.bluemalic.repair.entity.SysUserRole;
import com.bluemalic.repair.entity.Ticket;
import com.bluemalic.repair.entity.TicketLog;
import com.bluemalic.repair.mapper.NotificationMapper;
import com.bluemalic.repair.mapper.SysUserMapper;
import com.bluemalic.repair.mapper.SysUserRoleMapper;
import com.bluemalic.repair.mapper.TicketLogMapper;
import com.bluemalic.repair.mapper.TicketMapper;
import com.bluemalic.repair.service.TimeoutService;
import com.bluemalic.repair.service.TicketService;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 未接单超时提醒（M3）：20 待接单 超过阈值（默认 24h）仍无人接单 → 提醒本租户后勤管理员。
 *
 * <p>职责边界值得钉住的两点：
 * <ul>
 *   <li><b>接收者是"调度方"</b>：通知落到角色 code = ADMIN 的启用用户头上，不是学生也不是维修工</li>
 *   <li><b>幂等</b>：兜底扫描每分钟都会扫到同一批超期工单，靠 ticket_log 里的 ACCEPT_TIMEOUT
 *       记录判重——不这么做管理员会被同一条提醒每分钟刷一次</li>
 * </ul>
 * 测试直接调服务方法模拟调度器（@Scheduled 在测试里已关闭，见 @IntegrationTest）。
 */
@IntegrationTest
class TimeoutAcceptReminderTest {

    private static final long TENANT_ID = 1L;
    private static final long ROLE_ADMIN = 3L;

    @Autowired
    private TicketMapper ticketMapper;

    @Autowired
    private TicketLogMapper ticketLogMapper;

    @Autowired
    private NotificationMapper notificationMapper;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private SysUserRoleMapper sysUserRoleMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private TimeoutService timeoutService;

    @Autowired
    private TicketService ticketService;

    private final List<Long> createdTickets = new ArrayList<>();

    @AfterEach
    void cleanZSetMembers() {
        createdTickets.forEach(timeoutService::cancel);
    }

    @Test
    void dueAcceptDeadlineRemindsAdminsOnlyOnce() {
        long adminId = givenAdmin();
        Ticket overdue = ticketAt(20, LocalDateTime.now().minusHours(25));
        createdTickets.add(overdue.getId());
        timeoutService.registerAcceptDeadline(overdue.getId(), Instant.now().minusSeconds(60));

        // 模拟调度器：消费到期成员 → 执行提醒。
        // 只处理自己造的那条：Redis 不受测试事务回滚保护，早先失败的运行可能留下"工单已回滚、
        // ZSet 成员还在"的幽灵 id（生产里由调度器 catch 后跳过，测试要对齐这个行为）
        List<Long> due = timeoutService.handleDueAccept();
        assertThat(due).contains(overdue.getId());
        ticketService.remindAcceptTimeout(overdue.getId());

        assertLogCount(overdue.getId(), TicketAction.ACCEPT_TIMEOUT, 1);
        assertThat(noticesTo(adminId)).isEqualTo(1);

        // 幂等：兜底扫描路径再提醒一次，不应产生第二条（否则每分钟刷一条）
        ticketService.remindAcceptTimeout(overdue.getId());
        assertLogCount(overdue.getId(), TicketAction.ACCEPT_TIMEOUT, 1);
        assertThat(noticesTo(adminId)).isEqualTo(1);
    }

    @Test
    void notDueOrAlreadyAcceptedTicketsAreSkipped() {
        long adminId = givenAdmin();

        // 未到期：到期判定在扫描/队列侧，兜底扫描不应选中它（服务方法本身不做时间判断）
        Ticket fresh = ticketAt(20, LocalDateTime.now().minusMinutes(5));
        createdTickets.add(fresh.getId());
        assertThat(timeoutService.backstopScanAccept()).doesNotContain(fresh.getId());

        // 已接单（30 处理中）：即使派单时间很早、队列也到期了，也不该提醒（状态判定在服务侧）
        Ticket accepted = ticketAt(30, LocalDateTime.now().minusHours(25));
        createdTickets.add(accepted.getId());
        timeoutService.registerAcceptDeadline(accepted.getId(), Instant.now().minusSeconds(60));
        assertThat(timeoutService.handleDueAccept()).contains(accepted.getId());
        ticketService.remindAcceptTimeout(accepted.getId());
        assertLogCount(accepted.getId(), TicketAction.ACCEPT_TIMEOUT, 0);
        assertThat(noticesTo(adminId)).isZero();
    }

    @Test
    void backstopFindsOverdueUnacceptedTickets() {
        Ticket overdue = ticketAt(20, LocalDateTime.now().minusHours(25));
        createdTickets.add(overdue.getId());
        assertThat(timeoutService.backstopScanAccept()).contains(overdue.getId());
    }

    // ==================== 工具 ====================

    /** 造一个本租户的后勤管理员（角色 3 = ADMIN，种子数据里的平台内置角色）。 */
    private long givenAdmin() {
        SysUser admin = new SysUser();
        admin.setTenantId(TENANT_ID);
        admin.setUsername("test-accept-admin");
        admin.setPassword(passwordEncoder.encode("Test@123456"));
        admin.setRealName("超时提醒管理员");
        admin.setUserType(3);
        admin.setStatus(1);
        sysUserMapper.insert(admin);

        SysUserRole link = new SysUserRole();
        link.setUserId(admin.getId());
        link.setRoleId(ROLE_ADMIN);
        sysUserRoleMapper.insert(link);
        return admin.getId();
    }

    private Ticket ticketAt(int status, LocalDateTime dispatchAt) {
        Ticket ticket = new Ticket();
        ticket.setTenantId(TENANT_ID);
        ticket.setTicketNo("TAR" + System.nanoTime());
        ticket.setStudentId(910000L);
        ticket.setWorkerId(status == 30 ? 910001L : null);
        ticket.setBuildingId(1L);
        ticket.setRoom("1-101");
        ticket.setCategoryId(1L);
        ticket.setUrgency(1);
        ticket.setStatus(status);
        ticket.setSubmitTime(dispatchAt.minusMinutes(10));
        ticket.setDispatchTime(dispatchAt);
        if (status == 30) {
            ticket.setAcceptTime(dispatchAt.plusMinutes(5));
        }
        ticketMapper.insert(ticket);
        return ticket;
    }

    private long noticesTo(long receiverId) {
        return notificationMapper.selectCount(Wrappers.<Notification>lambdaQuery()
                .eq(Notification::getReceiverId, receiverId)
                .eq(Notification::getType, "TICKET_ACCEPT_TIMEOUT"));
    }

    private void assertLogCount(Long ticketId, TicketAction action, long expected) {
        assertThat(ticketLogMapper.selectCount(Wrappers.<TicketLog>lambdaQuery()
                .eq(TicketLog::getTicketId, ticketId)
                .eq(TicketLog::getAction, action.name()))).isEqualTo(expected);
    }
}