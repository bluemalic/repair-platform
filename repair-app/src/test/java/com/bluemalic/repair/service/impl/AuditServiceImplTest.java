package com.bluemalic.repair.service.impl;

import com.bluemalic.repair.common.AuditAction;
import com.bluemalic.repair.entity.AuditLog;
import com.bluemalic.repair.mapper.AuditLogMapper;
import com.bluemalic.repair.mapper.SysUserMapper;
import com.bluemalic.repair.service.CurrentTenantService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 「登录审计写失败**不阻断登录**」这条规矩的守卫（`docs/01` §4.4）。
 *
 * <p>其它审计方法的规矩正好相反：审计写失败 → 业务也失败（宁可操作失败，也不要改了东西却查不到谁改的）。
 * 登录是唯一的例外——它没有改任何业务数据，而它一失败就是全校都登不进去，**包括唯一能来修表的管理员**。
 * 所以这里用一个"写就炸"的假 mapper，断言两个登录方法都不往外抛。
 *
 * <p>纯单测（不起 Spring）：这条性质只跟"有没有 try/catch"有关，用真库反而更难造出写入失败。
 */
class AuditServiceImplTest {

    private final AuditLogMapper auditLogMapper = mock(AuditLogMapper.class);

    private final SysUserMapper sysUserMapper = mock(SysUserMapper.class);

    private final CurrentTenantService currentTenantService = mock(CurrentTenantService.class);

    private final StringRedisTemplate stringRedisTemplate = mock(StringRedisTemplate.class);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<HttpServletRequest> requestProvider = mock(ObjectProvider.class);

    private AuditServiceImpl service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.setIfAbsent(anyString(), anyString(), any(Duration.class))).thenReturn(true);
        service = new AuditServiceImpl(auditLogMapper, sysUserMapper, currentTenantService,
                stringRedisTemplate, requestProvider);
    }

    @Test
    void loginSuccessAuditFailureDoesNotBreakLogin() {
        when(auditLogMapper.insert(any(AuditLog.class))).thenThrow(new IllegalStateException("审计表不可用"));

        assertThatCode(() -> service.recordLoginSuccess(1L, 7L, "worker01")).doesNotThrowAnyException();
    }

    @Test
    void loginFailureAuditFailureDoesNotBreakLogin() {
        when(auditLogMapper.insert(any(AuditLog.class))).thenThrow(new IllegalStateException("审计表不可用"));

        assertThatCode(() -> service.recordLoginFailure(1L, null, "nobody",
                AuditAction.LOGIN_FAILED, "账号不存在")).doesNotThrowAnyException();
    }

    @Test
    void redisFailureMeansRecordAnyway() {
        // 去重键拿不到时按"没登录过"处理：宁可多记几条，也不要因为缓存不可用就漏掉登录事件
        when(stringRedisTemplate.opsForValue()).thenThrow(new IllegalStateException("Redis 不可用"));

        assertThatCode(() -> service.recordLoginSuccess(1L, 7L, "worker01")).doesNotThrowAnyException();
    }
}
