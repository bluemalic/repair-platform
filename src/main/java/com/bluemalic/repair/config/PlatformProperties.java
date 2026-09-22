package com.bluemalic.repair.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 平台运营账号的引导配置。**默认不引导**（两个变量都留空），这是常态——
 * 租户自己的部署、本地开发都不需要平台账号。
 *
 * <ul>
 *   <li>{@code REPAIR_PLATFORM_BOOTSTRAP_USERNAME}：平台运营账号的登录名</li>
 *   <li>{@code REPAIR_PLATFORM_BOOTSTRAP_PASSWORD}：它的初始口令（**首次登录必须改**）</li>
 *   <li>{@code REPAIR_PLATFORM_BOOTSTRAP_REAL_NAME}：显示名（默认"平台运营"）</li>
 * </ul>
 *
 * <p><b>为什么平台账号由环境变量引导，而不是像首个后勤管理员那样手写 SQL</b>：手工插库要操作者
 * 记对三个魔法值（{@code tenant_id = 0}、{@code user_type = 4}、{@code role_id = 4}），
 * 错一个的现象是"用户名或密码错误"——看不出真正原因（docs/05 §4.3 自己吐槽过这一点）。
 * 引导只要求填两个变量，口令不落 Git，账号建出来还自带角色关联。
 *
 * <p><b>只建不覆盖</b>：账号已存在时什么都不做（见 {@code AccountService#ensurePlatformAccount}）。
 * 否则每次重启都会把改过的口令打回环境变量里的值，"首次登录必须改密"也就白做了。
 * 忘记平台口令的恢复路径因此是"删掉这个账号再重启"。
 *
 * <p><b>只填了一个就启动报错</b>（照 {@code DemoProperties} 的理由）：漏填口令而静默跳过的话，
 * 现象是"平台账号登不上"，排查要绕一圈才知道是配置没填全。
 */
@Getter
@Component
public class PlatformProperties {

    private final String bootstrapUsername;

    private final String bootstrapPassword;

    private final String bootstrapRealName;

    public PlatformProperties(@Value("${repair.platform.bootstrap.username:}") String bootstrapUsername,
                             @Value("${repair.platform.bootstrap.password:}") String bootstrapPassword,
                             @Value("${repair.platform.bootstrap.real-name:平台运营}") String bootstrapRealName) {
        boolean hasUsername = StringUtils.hasText(bootstrapUsername);
        boolean hasPassword = StringUtils.hasText(bootstrapPassword);
        if (hasUsername != hasPassword) {
            throw new IllegalStateException(
                    "平台运营账号的引导配置只填了一半：REPAIR_PLATFORM_BOOTSTRAP_USERNAME 与 "
                            + "REPAIR_PLATFORM_BOOTSTRAP_PASSWORD 要么都填、要么都不填。"
                            + "（两个都留空 = 不引导平台账号，这是常态）");
        }
        this.bootstrapUsername = bootstrapUsername;
        this.bootstrapPassword = bootstrapPassword;
        this.bootstrapRealName = bootstrapRealName;
    }

    /** 是否配置了引导。两个变量都填了才算。 */
    public boolean bootstrapConfigured() {
        return StringUtils.hasText(bootstrapUsername) && StringUtils.hasText(bootstrapPassword);
    }
}
