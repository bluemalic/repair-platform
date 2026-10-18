package com.bluemalic.repair.job;

import com.bluemalic.repair.service.impl.AuthorizationCacheService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * 启动后清一次鉴权缓存（阶段 5 · ADR-020）。
 *
 * <p>为什么需要它：Redis 是**跨进程存活**的，上一个进程写下的"用户 → 权限码"缓存会留在这里。
 * 而权限点由 {@code R__seed.sql} 在启动时维护——如果这次发布改了某个角色的权限，
 * 不清缓存的话最多要等 TTL（30 分钟）才生效，现象是"新权限码明明在库里，接口还是 403"。
 *
 * <p>放在 {@code ApplicationReadyEvent}：此时 Flyway（含 R__seed）已经跑完，
 * 清掉的是"旧进程的口径"。多实例部署时互相清也无害——缓存本来就是可丢的。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuthorizationCacheBootstrap {

    private final AuthorizationCacheService authorizationCacheService;

    @EventListener(ApplicationReadyEvent.class)
    public void evictStaleAuthorizationCache() {
        authorizationCacheService.evictAll();
    }
}
