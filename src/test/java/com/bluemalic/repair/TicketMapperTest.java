package com.bluemalic.repair;

import com.bluemalic.repair.entity.Ticket;
import com.bluemalic.repair.mapper.TicketMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 验证 ticket 表：27 个字段的映射，以及 json 列（images / resultImages）的读写。
 *
 * <p>为什么要专门测 json：它的 TypeHandler 只在**查询**时依赖 {@code autoResultMap = true}，
 * 插入时不依赖。也就是说"能存进去"证明不了配置是对的，必须查回来比对。
 */
@SpringBootTest
@Transactional
class TicketMapperTest {

    @Autowired
    private TicketMapper ticketMapper;

    @Test
    void jsonColumnsRoundTrip() {
        Ticket ticket = new Ticket();
        ticket.setTenantId(1L);
        ticket.setTicketNo("WX20260912999");
        ticket.setStudentId(1L);
        ticket.setBuildingId(1L);
        ticket.setRoom("1-101");
        ticket.setCategoryId(1L);
        ticket.setUrgency(1);
        ticket.setStatus(10);
        ticket.setSubmitTime(LocalDateTime.now());
        ticket.setImages(List.of("https://example.com/a.jpg", "https://example.com/b.jpg"));
        ticket.setResultImages(List.of());

        assertThat(ticketMapper.insert(ticket)).isEqualTo(1);
        assertThat(ticket.getId()).isNotNull();

        Ticket loaded = ticketMapper.selectById(ticket.getId());
        assertThat(loaded).isNotNull();
        assertThat(loaded.getTicketNo()).isEqualTo("WX20260912999");
        assertThat(loaded.getImages())
                .hasSize(2)
                .containsExactly("https://example.com/a.jpg", "https://example.com/b.jpg");
        assertThat(loaded.getResultImages()).isEmpty();
        // 未派单，worker_id 应为 null —— 证明可空列没有被写成 0 之类的默认值
        assertThat(loaded.getWorkerId()).isNull();
    }
}
