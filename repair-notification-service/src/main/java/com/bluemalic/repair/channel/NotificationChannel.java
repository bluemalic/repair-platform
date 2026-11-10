package com.bluemalic.repair.channel;

/**
 * 通知通道 SPI（阶段 4 · ADR-030）。
 *
 * <p><b>站内信不走这个接口的"外部投递"路径</b>：它是事实来源，必须在业务事务里写成功。
 * 这里的外部通道是可选适配器，失败不影响站内信，只影响自己的 delivery 行。
 *
 * <p>实现类由 {@code NotificationDeliveryServiceImpl} 按 {@link #channelType()} 索引；
 * {@link #isEnabled(long)} 为 false 时**既不投递也不写 delivery 行**——
 * "关掉通道"必须是零痕迹的，否则表里会堆一堆永远不会被处理的 PENDING。
 */
public interface NotificationChannel {

    /** 通道码：IN_APP / WECOM / SMS / EMAIL（与 notification_delivery.channel 一致）。 */
    String channelType();

    /** 该租户是否启用了这个通道（首版只看全局开关，不建租户级配置表）。 */
    boolean isEnabled(long tenantId);

    /**
     * 投递。
     *
     * @throws RuntimeException 失败即可（调用方负责退避与死信），异常消息会截断后写 last_error，
     *                          **不要把密钥、手机号放进异常消息**
     */
    void send(NotificationMessage message);
}
