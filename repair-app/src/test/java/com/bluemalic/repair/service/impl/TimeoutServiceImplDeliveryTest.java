package com.bluemalic.repair.service.impl;

import com.bluemalic.repair.config.TimeoutProperties;
import com.bluemalic.repair.config.TimeoutRule;
import com.bluemalic.repair.mapper.TicketEvaluationMapper;
import com.bluemalic.repair.mapper.TicketMapper;
import com.bluemalic.repair.mq.TimeoutMqScheduler;
import com.bluemalic.repair.tenancy.TenantSchemaCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * delivery=mq 时分流语义：登记走 MQ 定时消息（不碰 Redis），取消是逻辑取消（不删任何东西）。
 * zset 分支由现有超时集成测试覆盖，这里只钉住新增的分流。
 */
class TimeoutServiceImplDeliveryTest {

    @Test
    void mqDeliverySchedulesTimerMessageInsteadOfZSet() {
        TimeoutMqScheduler scheduler = mock(TimeoutMqScheduler.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<TimeoutMqScheduler> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(scheduler);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);

        TimeoutServiceImpl service = newService(redis, provider, "mq");
        service.registerAccept(7L, 3L);

        verify(scheduler).schedule(eq("ACCEPT"), eq(7L), eq(3L), any(Instant.class));
        verifyNoInteractions(redis);
    }

    @Test
    void cancelIsLogicalOnlyInMqMode() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<TimeoutMqScheduler> provider = mock(ObjectProvider.class);
        TimeoutServiceImpl service = newService(redis, provider, "mq");

        service.cancel(7L, 3L);

        // 定时消息无法撤回：不应再去删 Redis（zset 里本来也没有）
        verifyNoInteractions(redis);
    }

    private TimeoutServiceImpl newService(StringRedisTemplate redis,
                                          ObjectProvider<TimeoutMqScheduler> provider,
                                          String delivery) {
        return new TimeoutServiceImpl(
                redis,
                new TimeoutRule(1440, 2880, 1440),
                mock(TicketMapper.class),
                mock(TicketEvaluationMapper.class),
                mock(TenantSchemaCatalog.class),
                new TimeoutProperties(delivery, true, false, "t", "pg", "cg"),
                provider);
    }
}
