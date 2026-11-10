package com.bluemalic.repair.service;

/**
 * 当前登录者的租户上下文（ADR-008 的配套）。
 *
 * <p>为什么需要它：数据权限拦截器要在**每次查询/更新**前注入租户条件，而它只能拿到 loginId，
 * 拿不到租户——如果每次都去查 `sys_user`，每个请求就多一次库往返（评审也点了这一条）。
 * 所以登录时把 tenantId 写进 Sa-Token Session，之后从 Session 读；Session 里没有时（老 token、
 * 或者别处直接构造的登录态）再回查一次库并回填，做到自愈。
 */
public interface CurrentTenantService {

    /** Session 里存租户 ID 的键。 */
    String SESSION_TENANT_KEY = "tenantId";

    /** 登录成功后绑定租户，后续请求从 Session 读。 */
    void bind(Long tenantId);

    /** 当前登录者的租户 ID；**无登录态（定时任务等系统上下文）返回 null**。 */
    Long tenantIdOrNull();

    /** 当前登录者的租户 ID；无登录态直接抛未登录——业务接口用这个。 */
    Long requireTenantId();
}