package com.bluemalic.repair.channel;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 企业微信群机器人通道（阶段 4 · **占位实现，不调外部**）。
 *
 * <p>{@code repair.notification.wecom.enabled} 默认 false：关着的时候连 delivery 行都不写，
 * 系统行为与没有这个通道完全一致（ADR-030 决定 7）。**即使误开**，{@link #send} 也会明确失败
 * 并进死信——不会静默丢消息，也不会真的去访问外部接口（密钥、采购、合规都还没定，ADR-030 决定 6）。
 *
 * <p>真要接的时候，这里补三件事：webhook 地址从环境变量读（不落库明文）、
 * 按 receiver 映射企微 userid、失败按 HTTP 语义分类（4xx 直接死信，5xx 退避）。
 */
@Slf4j
@Component
public class WeComChannel implements NotificationChannel {

    public static final String TYPE = "WECOM";

    @Value("${repair.notification.wecom.enabled:false}")
    private boolean enabled;

    @Override
    public String channelType() {
        return TYPE;
    }

    @Override
    public boolean isEnabled(long tenantId) {
        return enabled;
    }

    @Override
    public void send(NotificationMessage message) {
        // 刻意抛错而不是"假装成功"：开了开关却发不出去，必须看得见（进 DEAD + 告警）
        throw new IllegalStateException("企业微信通道尚未接入（ADR-030：外部通道默认关闭）");
    }
}
