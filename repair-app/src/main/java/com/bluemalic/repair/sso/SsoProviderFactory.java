package com.bluemalic.repair.sso;

import org.springframework.stereotype.Component;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * 协议 → 适配器（阶段 7 · ADR-022）。
 *
 * <p>加一种协议 = 新增一个 {@link SsoProvider} 实现（`@Component`），这里自动收进来；
 * 不需要改注册代码，也不需要在别处写 if/else。
 */
@Component
public class SsoProviderFactory {

    private final Map<SsoProtocol, SsoProvider> providers;

    public SsoProviderFactory(List<SsoProvider> providers) {
        Map<SsoProtocol, SsoProvider> map = new EnumMap<>(SsoProtocol.class);
        providers.forEach(provider -> map.put(provider.protocol(), provider));
        this.providers = Map.copyOf(map);
    }

    public SsoProvider of(SsoProtocol protocol) {
        SsoProvider provider = providers.get(protocol);
        if (provider == null) {
            // 配置里写了某种协议、但代码里没有对应实现：这是部署问题，不是用户输入问题
            throw new IllegalStateException("没有实现统一认证协议 " + protocol + " 的适配器");
        }
        return provider;
    }
}
