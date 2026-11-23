package com.bluemalic.repair;

import com.bluemalic.repair.common.TicketAction;
import com.bluemalic.repair.entity.SysUser;
import com.bluemalic.repair.entity.SysUserRole;
import com.bluemalic.repair.entity.Ticket;
import com.bluemalic.repair.entity.TicketLog;
import com.bluemalic.repair.mapper.SysUserMapper;
import com.bluemalic.repair.mapper.SysUserRoleMapper;
import com.bluemalic.repair.mapper.TicketLogMapper;
import com.bluemalic.repair.mapper.TicketMapper;
import com.bluemalic.repair.service.DueTicket;
import com.bluemalic.repair.service.impl.TicketTimeoutHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/** Ensures concurrent timeout workers cannot send the same reminder twice. */
@IntegrationTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class TimeoutReminderConcurrencyTest {

    private static final long TENANT_ID = 1L;
    private static final long ROLE_ADMIN = 3L;

    @Autowired
    private TicketTimeoutHandler timeoutHandler;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private SysUserRoleMapper sysUserRoleMapper;

    @Autowired
    private TicketMapper ticketMapper;

    @Autowired
    private TicketLogMapper ticketLogMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private Long ticketId;
    private Long adminId;

    @Test
    void concurrentAcceptRemindersWriteOneLogAndNotification() throws Exception {
        adminId = createAdmin();
        ticketId = createTicket();
        DueTicket due = new DueTicket(TENANT_ID, ticketId);

        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        try {
            Future<?> first = pool.submit(() -> runAfter(start, due));
            Future<?> second = pool.submit(() -> runAfter(start, due));
            start.countDown();
            first.get();
            second.get();
        } finally {
            pool.shutdownNow();
        }

        assertThat(ticketLogMapper.selectCount(
                com.baomidou.mybatisplus.core.toolkit.Wrappers.<TicketLog>lambdaQuery()
                        .eq(TicketLog::getTicketId, ticketId)
                        .eq(TicketLog::getAction, TicketAction.ACCEPT_TIMEOUT.name())))
                .isEqualTo(1);
        Integer notifications = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notification WHERE ticket_id = ? AND receiver_id = ? AND type = ?",
                Integer.class, ticketId, adminId, "TICKET_ACCEPT_TIMEOUT");
        assertThat(notifications).isEqualTo(1);
    }

    private void runAfter(CountDownLatch start, DueTicket due) {
        try {
            start.await();
            timeoutHandler.remindAcceptTimeout(due);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private long createAdmin() {
        SysUser admin = new SysUser();
        admin.setTenantId(TENANT_ID);
        admin.setUsername("test-tmo-" + System.nanoTime());
        admin.setPassword("$2a$10$7EqJtq98hPqEX7fNZaFWoOe5fLqM0Vf3k8Yq7fY4H8H2y2a3G8H5K");
        admin.setRealName("并发提醒管理员");
        admin.setUserType(3);
        admin.setStatus(1);
        admin.setMustChangePassword(0);
        sysUserMapper.insert(admin);
        adminId = admin.getId();

        SysUserRole role = new SysUserRole();
        role.setUserId(admin.getId());
        role.setRoleId(ROLE_ADMIN);
        sysUserRoleMapper.insert(role);
        return admin.getId();
    }

    private long createTicket() {
        Ticket ticket = new Ticket();
        ticket.setTenantId(TENANT_ID);
        ticket.setTicketNo("TRC" + System.nanoTime());
        ticket.setStudentId(930000L);
        ticket.setBuildingId(1L);
        ticket.setRoom("1-101");
        ticket.setCategoryId(1L);
        ticket.setUrgency(1);
        ticket.setStatus(20);
        ticket.setSubmitTime(LocalDateTime.now().minusHours(26));
        ticket.setDispatchTime(LocalDateTime.now().minusHours(25));
        ticketMapper.insert(ticket);
        return ticket.getId();
    }

    @AfterEach
    void cleanup() {
        if (ticketId != null) {
            jdbcTemplate.update("DELETE FROM notification WHERE ticket_id = ?", ticketId);
            jdbcTemplate.update("DELETE FROM ticket_log WHERE ticket_id = ?", ticketId);
            jdbcTemplate.update("DELETE FROM ticket WHERE id = ?", ticketId);
        }
        if (adminId != null) {
            jdbcTemplate.update("DELETE FROM sys_user_role WHERE user_id = ?", adminId);
            jdbcTemplate.update("DELETE FROM sys_user WHERE id = ?", adminId);
        }
    }
}
