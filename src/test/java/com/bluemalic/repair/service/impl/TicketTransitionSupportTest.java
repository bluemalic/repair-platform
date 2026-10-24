package com.bluemalic.repair.service.impl;

import com.bluemalic.repair.common.MetricsRecorder;
import com.bluemalic.repair.common.TicketAction;
import com.bluemalic.repair.entity.Ticket;
import com.bluemalic.repair.mapper.BuildingMapper;
import com.bluemalic.repair.mapper.RepairCodeMapper;
import com.bluemalic.repair.mapper.SysUserMapper;
import com.bluemalic.repair.mapper.TicketCategoryMapper;
import com.bluemalic.repair.mapper.TicketCollaboratorMapper;
import com.bluemalic.repair.mapper.TicketLogMapper;
import com.bluemalic.repair.mapper.TicketMapper;
import com.bluemalic.repair.mapper.WorkerBuildingMapper;
import com.bluemalic.repair.service.NotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class TicketTransitionSupportTest {

    @Test
    void transferNoticeWithNoWorkerIsSkippedInsteadOfThrowing() {
        NotificationService notificationService = mock(NotificationService.class);
        TicketTransitionSupport support = new TicketTransitionSupport(
                mock(TicketMapper.class),
                mock(BuildingMapper.class),
                mock(RepairCodeMapper.class),
                mock(SysUserMapper.class),
                mock(TicketCategoryMapper.class),
                mock(TicketCollaboratorMapper.class),
                mock(WorkerBuildingMapper.class),
                mock(StringRedisTemplate.class),
                mock(TicketLogMapper.class),
                notificationService,
                mock(MetricsRecorder.class));

        Ticket ticket = new Ticket();
        ticket.setTenantId(1L);
        ticket.setStudentId(2L);
        ticket.setTicketNo("WX20260101001");
        // workerId 为 null：转派 / 管理员驳回清空主责后可能出现。
        // 修复前这里对 Long 拆箱会 NPE，接口报 500；修复后应静默跳过。
        support.notifyTransition(ticket, TicketAction.TRANSFER, null, "已转给新师傅");

        verifyNoInteractions(notificationService);
    }
}
