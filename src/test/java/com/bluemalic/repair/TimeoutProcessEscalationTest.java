package com.bluemalic.repair;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
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
import com.bluemalic.repair.config.TimeoutRule;
import com.bluemalic.repair.service.TimeoutService;
import com.bluemalic.repair.service.TicketService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 未处理超时升级（M3，docs/01：48h 未处理升级）：派单后超过阈值仍停在 30 处理中 → 升级提醒后勤管理员。
 *
 * <p>基准时间是 <b>dispatch_time</b>（不是 accept_time）：需求口径是"从派到完工"整体超期，
 * 这样"接了单但一直拖着不完工"的工单也会被升级——这正是要压住的场景。
 *
 * <p>与接单提醒同构：不是状态跃迁（30 → 30 记日志），幂等靠 ticket_log 里的 PROCESS_TIMEOUT 记录。
 */
@IntegrationTest
class TimeoutProcessEscalationTest {

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

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private TimeoutRule timeoutRule;

    private final List<Long> createdTickets = new ArrayList<>();

    @AfterEach
    void cleanZSetMembers() {
        createdTickets.forEach(timeoutService::cancel);
    }

    @Test
    void dueProcessDeadlineEscalatesAdminsOnlyOnce() {
        long adminId = givenAdmin();
        Ticket overdue = ticketAt(30, LocalDateTime.now().minusHours(49));
        createdTickets.add(overdue.getId());
        timeoutService.registerProcessDeadline(overdue.getId(), Instant.now().minusSeconds(60));

        timeoutService.handleDueProcess().forEach(ticketService::escalateProcessTimeout);

        assertLogCount(overdue.getId(), TicketAction.PROCESS_TIMEOUT, 1);
        assertThat(noticesTo(adminId)).isEqualTo(1);

        // 幂等：兜底扫描路径再触发一次，不应产生第二条
        ticketService.escalateProcessTimeout(overdue.getId());
        assertLogCount(overdue.getId(), TicketAction.PROCESS_TIMEOUT, 1);
        assertThat(noticesTo(adminId)).isEqualTo(1);
    }

    @Test
    void notDueOrAlreadyFinishedTicketsAreSkipped() {
        long adminId = givenAdmin();

        // 未到期：到期判定在扫描/队列侧
        Ticket fresh = ticketAt(30, LocalDateTime.now().minusMinutes(5));
        createdTickets.add(fresh.getId());
        assertThat(timeoutService.backstopScanProcess()).doesNotContain(fresh.getId());

        // 已完工（40 待验收）：即使派单时间很早、队列也到期了，也不该升级
        Ticket finished = ticketAt(40, LocalDateTime.now().minusHours(49));
        createdTickets.add(finished.getId());
        timeoutService.registerProcessDeadline(finished.getId(), Instant.now().minusSeconds(60));
        timeoutService.handleDueProcess().forEach(ticketService::escalateProcessTimeout);
        assertLogCount(finished.getId(), TicketAction.PROCESS_TIMEOUT, 0);
        assertThat(noticesTo(adminId)).isZero();
    }

    @Test
    void registerProcessUsesDispatchTimeAsBasis() {
        LocalDateTime dispatchAt = LocalDateTime.now().minusHours(30);
        Ticket ticket = ticketAt(30, dispatchAt);
        createdTickets.add(ticket.getId());

        timeoutService.registerProcess(ticket.getId(), ticket.getDispatchTime());

        Double score = stringRedisTemplate.opsForZSet().score("ticket:timeout:PROCESS", String.valueOf(ticket.getId()));
        assertThat(score).isNotNull();
        // 与兜底扫描同基准：派单时间 + 阈值（若用"现在 + 阈值"就会比这里晚 30 小时）
        long expected = dispatchAt.plus(timeoutRule.processDuration())
                .atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        assertThat(score.longValue()).isEqualTo(expected);
    }

    @Test
    void backstopFindsOverdueProcessingTickets() {
        Ticket overdue = ticketAt(30, LocalDateTime.now().minusHours(49));
        createdTickets.add(overdue.getId());
        assertThat(timeoutService.backstopScanProcess()).contains(overdue.getId());
    }

    // ==================== 工具 ====================

    private long givenAdmin() {
        SysUser admin = new SysUser();
        admin.setTenantId(TENANT_ID);
        admin.setUsername("test-escalate-admin");
        admin.setPassword(passwordEncoder.encode("Test@123456"));
        admin.setRealName("超时升级管理员");
        admin.setUserType(3);
        admin.setStatus(1);
        sysUserMapper.insert(admin);

        SysUserRole link = new SysUserRole();
        link.setUserId(admin.getId());
        link.setRoleId(ROLE_ADMIN);
        sysUserRoleMapper.insert(link);
        return admin.getId();
    }

    /** status=30 表示已接单；40 表示已完工待验收。dispatchTime 是超时基准。 */
    private Ticket ticketAt(int status, LocalDateTime dispatchAt) {
        Ticket ticket = new Ticket();
        ticket.setTenantId(TENANT_ID);
        ticket.setTicketNo("TPE" + System.nanoTime());
        ticket.setStudentId(920000L);
        ticket.setWorkerId(920001L);
        ticket.setBuildingId(1L);
        ticket.setRoom("1-101");
        ticket.setCategoryId(1L);
        ticket.setUrgency(1);
        ticket.setStatus(status);
        ticket.setSubmitTime(dispatchAt.minusMinutes(10));
        ticket.setDispatchTime(dispatchAt);
        ticket.setAcceptTime(dispatchAt.plusMinutes(5));
        if (status == 40) {
            ticket.setFinishTime(dispatchAt.plusHours(20));
        }
        ticketMapper.insert(ticket);
        return ticket;
    }

    private long noticesTo(long receiverId) {
        return notificationMapper.selectCount(Wrappers.<Notification>lambdaQuery()
                .eq(Notification::getReceiverId, receiverId)
                .eq(Notification::getType, "TICKET_PROCESS_TIMEOUT"));
    }

    private void assertLogCount(Long ticketId, TicketAction action, long expected) {
        assertThat(ticketLogMapper.selectCount(Wrappers.<TicketLog>lambdaQuery()
                .eq(TicketLog::getTicketId, ticketId)
                .eq(TicketLog::getAction, action.name()))).isEqualTo(expected);
    }
}