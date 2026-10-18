package com.bluemalic.repair.service;

/**
 * 一条待处理的超时任务：**哪个租户的哪张工单**（阶段 6 第 3 步 · ADR-021）。
 *
 * <p><b>为什么要带租户，而不是只带工单 ID</b>：定时任务没有登录态，只有一张工单 ID 时
 * 根本不知道去哪个库找它。而 Redis 里的 ZSet 成员是**登记当时**写下的——学校切到自己的库之后，
 * 旧成员里那个"库"已经不对了。所以成员里存的是**租户 ID**，消费时再经
 * {@code TenantSchemaCatalog} 换成当前的库名：**切换前后登记的任务都会落到现在的那个库上**。
 *
 * <p>{@link #LEGACY_TENANT} 是过渡期兜底：本机制上线之前登记的成员只有工单 ID（没有租户）。
 * 那时所有学校都还在平台库，按平台库处理是对的——而且这批成员消费完就没了。
 */
public record DueTicket(long tenantId, long ticketId) {

    /** 过渡期里"没有租户信息"的老成员（只有工单 ID）：按平台库处理。 */
    public static final long LEGACY_TENANT = -1L;
}
