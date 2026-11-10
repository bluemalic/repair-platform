package com.bluemalic.repair.identity.api;

import java.util.Collection;
import java.util.List;

/**
 * identity 域对**其他服务**开放的只读契约（阶段 3.2）。
 *
 * <p>为什么现在要有它：ticket 域有 18 处直接注入 {@code SysUserMapper} / {@code SysRoleMapper}
 * 读身份数据（派单校验、详情页姓名、排班、评分候选）。阶段 3.3 把它们改成跨服务调用后，
 * 走的就是这组方法——先把形状定下来，3.3 只需要加一个 Feign 接口适配它，不必再改业务代码。
 *
 * <p><b>这是纯 Java 接口，不带任何 HTTP / Feign 注解</b>：本轮不引
 * {@code spring-cloud-starter-openfeign}（3.2 没有调用方，引了就是空依赖）。
 * HTTP 那一侧由 {@code InternalIdentityController} 提供，路径见 {@code docs/03 §4.1.1}。
 *
 * <p><b>每个方法都要显式传 tenantId</b>：内部调用没有登录态，路由键不能靠 Sa-Token 推，
 * 必须由调用方说清"问的是哪所学校"。
 */
public interface IdentityQueryApi {

    /** 单个用户；不存在返回 {@code null}（调用方决定是报错还是留空）。 */
    UserBriefVO findUser(long tenantId, long userId);

    /** 批量取（详情页、列表回填姓名用）；不保证顺序，缺失的 id 直接不出现在结果里。 */
    List<UserBriefVO> findUsers(long tenantId, Collection<Long> userIds);

    /** 维修工的负责楼栋与擅长类别（派单校验 / 评分维度）。 */
    WorkerScopeVO findWorkerScope(long tenantId, long workerId);

    /**
     * 这所学校是否还有**启用中的管理员**。
     *
     * <p>平台运营停用学校前拿它做提示；也用于开通流程收尾的自检（"管理员真的建出来了"）。
     */
    boolean existsEnabledAdmin(long tenantId);
}
