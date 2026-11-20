package com.bluemalic.repair;

import cn.dev33.satoken.stp.StpUtil;
import com.bluemalic.repair.common.BizException;
import com.bluemalic.repair.common.ErrorCode;
import com.bluemalic.repair.common.RateLimiter;
import com.bluemalic.repair.config.RateLimitRule;
import com.bluemalic.repair.interceptor.RateLimit;
import com.bluemalic.repair.interceptor.RateLimitInterceptor;
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

class RateLimitInterceptorAiTest {

    private final HttpServletRequest request = mock(HttpServletRequest.class);
    private final HttpServletResponse response = mock(HttpServletResponse.class);
    private final HandlerMethod aiHandler;

    RateLimitInterceptorAiTest() throws NoSuchMethodException {
        aiHandler = new HandlerMethod(new TestController(), TestController.class.getMethod("ai"));
    }

    @Test
    void enforcesSharedAiRequestWindowAcrossAccounts() throws Exception {
        RateLimiter rateLimiter = mock(RateLimiter.class);
        when(rateLimiter.increment(anyString(), anyInt())).thenReturn(1L, 1L, 1L, 2L);
        RateLimitInterceptor interceptor = new RateLimitInterceptor(rateLimiter,
                new RateLimitRule(60, 10, 60, 10, 1, 10, 30));

        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdDefaultNull).thenReturn("user-a", "user-b");
            assertThat(interceptor.preHandle(request, response, aiHandler)).isTrue();
            assertThatThrownBy(() -> interceptor.preHandle(request, response, aiHandler))
                    .isInstanceOf(BizException.class)
                    .extracting(e -> ((BizException) e).getErrorCode())
                    .isEqualTo(ErrorCode.TOO_MANY_REQUESTS);
        }

        verify(rateLimiter).increment("rate:user-a:tier:ai", 60);
        verify(rateLimiter).increment("rate:user-b:tier:ai", 60);
        org.mockito.Mockito.verify(rateLimiter, org.mockito.Mockito.times(2))
                .increment("rate:global:tier:ai", 60);
    }

    @Test
    void failsClosedWhenSharedAiCounterIsUnavailable() throws Exception {
        RateLimiter rateLimiter = mock(RateLimiter.class);
        when(rateLimiter.increment(anyString(), anyInt())).thenReturn(1L, (Long) null);
        RateLimitInterceptor interceptor = new RateLimitInterceptor(rateLimiter,
                new RateLimitRule(60, 10, 60, 10, 30, 10, 30));

        try (MockedStatic<StpUtil> stp = mockStatic(StpUtil.class)) {
            stp.when(StpUtil::getLoginIdDefaultNull).thenReturn("user-a");
            assertThatThrownBy(() -> interceptor.preHandle(request, response, aiHandler))
                    .isInstanceOf(BizException.class)
                    .extracting(e -> ((BizException) e).getErrorCode())
                    .isEqualTo(ErrorCode.AI_MODEL_UNAVAILABLE);
        }
    }

    static class TestController {
        @RateLimit(key = "ai")
        public void ai() {}
    }
}
