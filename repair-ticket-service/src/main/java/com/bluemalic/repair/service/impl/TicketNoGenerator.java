package com.bluemalic.repair.service.impl;

import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/** 工单号：WX + 日期 + 当日序号（Redis INCR），uk_ticket_no 兜底唯一。 */
@Component
@RequiredArgsConstructor
class TicketNoGenerator {

    private static final DateTimeFormatter TICKET_NO_DATE = DateTimeFormatter.ofPattern("yyyyMMdd");

    private final StringRedisTemplate stringRedisTemplate;

    String nextTicketNo() {
        String date = LocalDate.now().format(TICKET_NO_DATE);
        String seqKey = "ticket:no:seq:" + date;
        Long seq = stringRedisTemplate.opsForValue().increment(seqKey);
        stringRedisTemplate.expire(seqKey, Duration.ofDays(2));
        return "WX" + date + String.format("%06d", seq);
    }
}
