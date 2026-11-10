package com.bluemalic.repair.service.impl;

import com.bluemalic.repair.config.TimeoutProperties;
import com.bluemalic.repair.config.TimeoutRule;
import com.bluemalic.repair.mq.TimeoutMqScheduler;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 超时登记的两种投递方式（阶段 3.4 从 {@code TimeoutServiceImplDeliveryTest} 拆过来）。
 *
 * <p>钉住三条：
 * <ol>
 *   <li>{@code delivery=mq} 时走 RocketMQ 定时消息，**不碰 Redis**；</li>
 *   <li>mq 模式下 {@code cancel} 是逻辑取消（定时消息撤不回，靠状态 CAS 判重），不删任何东西；</li>
 *   <li>{@code delivery=zset}（默认）写 {@code ticket:timeout:{节点}}，成员是 {@code 租户|工单}。</li>
 * </ol>
 *
 * <p>为什么第 3 条要在纯单测里钉：成员格式漂开不会报错，只会让扫描侧永远解析不到——
 * 那是"登记了但永远不触发"的静默故障。
 */
class TimeoutRegistrarImplTest {

    @Test
    void mqDeliverySchedulesTimerMessageInsteadOfZSet() {
        TimeoutMqScheduler scheduler = mock(TimeoutMqScheduler.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<TimeoutMqScheduler> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(scheduler);
        StringRedisTemplate redis = mock(StringRedisTemplate.class);

        TimeoutRegistrarImpl service = newService(redis, provider, "mq");
        service.registerAccept(7L, 3L);

        verify(scheduler).schedule(eq("ACCEPT"), eq(7L), eq(3L), any(Instant.class));
        verifyNoInteractions(redis);
    }

    @Test
    void cancelIsLogicalOnlyInMqMode() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ObjectProvider<TimeoutMqScheduler> provider = mock(ObjectProvider.class);
        TimeoutRegistrarImpl service = newService(redis, provider, "mq");

        service.cancel(7L, 3L);

        // 定时消息无法撤回：不应再去删 Redis（zset 里本来也没有）
        verifyNoInteractions(redis);
    }

    @Test
    void zsetDeliveryWritesTenantAndTicketMember() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ZSetOperations<String, String> zSet = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(zSet);
        @SuppressWarnings("unchecked")
        ObjectProvider<TimeoutMqScheduler> provider = mock(ObjectProvider.class);

        TimeoutRegistrarImpl service = newService(redis, provider, "zset");
        service.registerAccept(7L, 3L);

        // 成员必须是「租户|工单」：扫描侧按这个格式解析，格式错了就是永远不触发
        verify(zSet).add(eq("ticket:timeout:ACCEPT"), eq("3|7"), anyDouble());
    }

    @Test
    void cancelInZsetModeClearsAllThreeNodes() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ZSetOperations<String, String> zSet = mock(ZSetOperations.class);
        when(redis.opsForZSet()).thenReturn(zSet);
        @SuppressWarnings("unchecked")
        ObjectProvider<TimeoutMqScheduler> provider = mock(ObjectProvider.class);

        TimeoutRegistrarImpl service = newService(redis, provider, "zset");
        service.cancel(7L, 3L);

        verify(zSet).remove("ticket:timeout:ACCEPT", "3|7");
        verify(zSet).remove("ticket:timeout:PROCESS", "3|7");
        verify(zSet).remove("ticket:timeout:EVAL", "3|7");
    }

    private TimeoutRegistrarImpl newService(StringRedisTemplate redis,
                                            ObjectProvider<TimeoutMqScheduler> provider,
                                            String delivery) {
        return new TimeoutRegistrarImpl(
                redis,
                new TimeoutRule(1440, 2880, 1440),
                new TimeoutProperties(delivery, true, false, "t", "pg", "cg"),
                provider);
    }
}
