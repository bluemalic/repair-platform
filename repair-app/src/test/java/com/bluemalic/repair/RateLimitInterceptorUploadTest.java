package com.bluemalic.repair;

import cn.dev33.satoken.session.SaSession;
import cn.dev33.satoken.stp.StpUtil;
import com.bluemalic.repair.common.BizException;
import com.bluemalic.repair.common.ErrorCode;
import com.bluemalic.repair.common.RateLimiter;
import com.bluemalic.repair.config.RateLimitRule;
import com.bluemalic.repair.interceptor.RateLimit;
import com.bluemalic.repair.interceptor.RateLimitInterceptor;
import com.bluemalic.repair.service.CurrentTenantService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.web.method.HandlerMethod;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RateLimitInterceptorUploadTest {

    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final HttpServletResponse response = mock(HttpServletResponse.class);
    private final HandlerMethod uploadHandler;

    RateLimitInterceptorUploadTest() throws NoSuchMethodException {
        uploadHandler = new HandlerMethod(new TestController(), TestController.class.getMethod("upload"));
    }

    @Test
    void enforcesTenantWideUploadWindowAcrossAccounts() throws Exception {
        RateLimiter rateLimiter = mock(RateLimiter.class);
        when(rateLimiter.increment(anyString(), anyInt())).thenReturn(1L, 1L, 1L, 2L);
        RateLimitInterceptor interceptor = new RateLimitInterceptor(rateLimiter,
                new RateLimitRule(60, 10, 60, 10, 30, 10, 1));
        SaSession tenantSession = tenantSession(7L);

        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdDefaultNull).thenReturn("user-a", "user-b");
            stp.when(() -> StpUtil.getSession(false)).thenReturn(tenantSession);

            assertThat(interceptor.preHandle(request, response, uploadHandler)).isTrue();
            assertThatThrownBy(() -> interceptor.preHandle(request, response, uploadHandler))
                    .isInstanceOf(BizException.class)
                    .extracting(e -> ((BizException) e).getErrorCode())
                    .isEqualTo(ErrorCode.TOO_MANY_REQUESTS);
        }

        verify(rateLimiter).increment("rate:user-a:tier:upload", 60);
        verify(rateLimiter).increment("rate:user-b:tier:upload", 60);
        org.mockito.Mockito.verify(rateLimiter, org.mockito.Mockito.times(2))
                .increment("rate:global:tenant:7:tier:upload", 60);
    }

    @Test
    void uploadWindowsAreIsolatedBetweenTenants() throws Exception {
        RateLimiter rateLimiter = mock(RateLimiter.class);
        when(rateLimiter.increment(anyString(), anyInt())).thenReturn(1L, 1L, 1L, 1L);
        RateLimitInterceptor interceptor = new RateLimitInterceptor(rateLimiter,
                new RateLimitRule(60, 10, 60, 10, 30, 10, 1));
        SaSession tenantA = tenantSession(7L);
        SaSession tenantB = tenantSession(8L);

        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdDefaultNull).thenReturn("user-a", "user-b");
            stp.when(() -> StpUtil.getSession(false)).thenReturn(tenantA, tenantB);

            assertThat(interceptor.preHandle(request, response, uploadHandler)).isTrue();
            assertThat(interceptor.preHandle(request, response, uploadHandler)).isTrue();
        }

        verify(rateLimiter).increment("rate:global:tenant:7:tier:upload", 60);
        verify(rateLimiter).increment("rate:global:tenant:8:tier:upload", 60);
    }

    @Test
    void failsClosedWhenUploadRateCounterIsUnavailable() throws Exception {
        RateLimiter rateLimiter = mock(RateLimiter.class);
        when(rateLimiter.increment(anyString(), anyInt())).thenReturn((Long) null);
        RateLimitInterceptor interceptor = new RateLimitInterceptor(rateLimiter,
                new RateLimitRule(60, 10, 60, 10, 30, 10, 30));

        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdDefaultNull).thenReturn("user-a");
            assertThatThrownBy(() -> interceptor.preHandle(request, response, uploadHandler))
                    .isInstanceOf(BizException.class)
                    .extracting(e -> ((BizException) e).getErrorCode())
                    .isEqualTo(ErrorCode.SYSTEM_ERROR);
        }
    }

    @Test
    void failsClosedWhenTenantUploadCounterIsUnavailable() throws Exception {
        RateLimiter rateLimiter = mock(RateLimiter.class);
        when(rateLimiter.increment(anyString(), anyInt())).thenReturn(1L, (Long) null);
        RateLimitInterceptor interceptor = new RateLimitInterceptor(rateLimiter,
                new RateLimitRule(60, 10, 60, 10, 30, 10, 30));
        SaSession tenantSession = tenantSession(7L);

        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdDefaultNull).thenReturn("user-a");
            stp.when(() -> StpUtil.getSession(false)).thenReturn(tenantSession);
            assertThatThrownBy(() -> interceptor.preHandle(request, response, uploadHandler))
                    .isInstanceOf(BizException.class)
                    .extracting(e -> ((BizException) e).getErrorCode())
                    .isEqualTo(ErrorCode.SYSTEM_ERROR);
        }
    }

    @Test
    void failsClosedWhenTenantCannotBeResolvedForGlobalQuota() throws Exception {
        RateLimiter rateLimiter = mock(RateLimiter.class);
        when(rateLimiter.increment(anyString(), anyInt())).thenReturn(1L);
        RateLimitInterceptor interceptor = new RateLimitInterceptor(rateLimiter,
                new RateLimitRule(60, 10, 60, 10, 30, 10, 30));

        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdDefaultNull).thenReturn("user-a");
            stp.when(() -> StpUtil.getSession(false)).thenReturn(null);
            assertThatThrownBy(() -> interceptor.preHandle(request, response, uploadHandler))
                    .isInstanceOf(BizException.class)
                    .extracting(e -> ((BizException) e).getErrorCode())
                    .isEqualTo(ErrorCode.SYSTEM_ERROR);
        }
    }

    private SaSession tenantSession(long tenantId) {
        SaSession session = mock(SaSession.class);
        when(session.get(CurrentTenantService.SESSION_TENANT_KEY)).thenReturn(tenantId);
        return session;
    }

    static class TestController {
        @RateLimit(key = "upload")
        public void upload() {}
    }
}
