package com.bluemalic.repair.service;

import com.bluemalic.repair.vo.AuditLogVO;
import com.bluemalic.repair.vo.PageResult;

import java.time.LocalDate;

/**
 * 操作审计（`docs/01` §4.4）：**账号与基础数据的写操作留痕**。
 *
 * <p>工单流转不在这里（它有 {@code ticket_log}）；读操作也不在（那是访问日志）。
 *
 * <p><b>调用方式是显式的</b>：在各 service 的写方法里调 {@link #record}，不用 AOP 注解——
 * 审计的价值全在"目标是谁、改了什么"这些语义上，切面只能记到"调了哪个接口"，价值有限；
 * 而且显式调用读代码时一眼能看出哪些操作被审计了（清单见 `docs/01` §4.4）。
 * 代价是漏记要靠测试兜住（`AuditLogTest` 逐个动作断言）。
 */
public interface AuditService {

    /**
     * 记一条审计。**在业务事务里调用**（不新开事务）：业务回滚时审计也跟着回滚——
     * "没有记录 = 没有发生"。反过来，审计写不进去会让这次业务操作失败，
     * 这是刻意的：宁可操作失败，也不要"操作成功了但查不到谁干的"。
     *
     * @param action     动作码，见 {@link com.bluemalic.repair.common.AuditAction}
     * @param targetType 目标类型：WORKER / STUDENT / BUILDING / CATEGORY / REPAIR_CODE / TENANT / ACCOUNT
     * @param targetId   目标 ID；批量导入这类没有单一目标时传 null
     * @param targetName 目标名称快照（工号 / 楼栋名 / 学校名…）
     * @param detail     一句人话摘要，**不要带敏感字段原值**（手机号只说"已更新"）
     */
    void record(String action, String targetType, Long targetId, String targetName, String detail);

    /**
     * 记一条**登录成功**（`docs/01` §4.4）。**按「账号 + 天 + 来源 IP」去重**：同一人同一天从同一处
     * 登录只留一条，换 IP 会另记一条（那是"账号被盗"最该看见的信号）。
     *
     * <p>与 {@link #record} 分开的理由：登录发生在**登录态建立之前**，拿不到"当前用户 / 当前租户"，
     * 所以租户与账号必须由调用方显式传进来（登录接口在口令校验前就已经知道是哪所学校的哪个账号了）。
     *
     * <p><b>它绝不抛异常</b>：写失败只记 ERROR 日志。其它审计方法的规矩是"审计失败 → 业务失败"，
     * 但登录没有改任何业务数据，而它一失败就是全校（包括唯一能修表的管理员）都登不进去——
     * 破例的理由写在 `docs/01` §4.4。
     */
    void recordLoginSuccess(long tenantId, long userId, String username);

    /**
     * 记一条**登录失败 / 停用账号尝试登录**。**不去重**——失败本身就是稀有事件，每次都要看得见
     * （"有人在试这个账号"正是靠次数看出来的）。
     *
     * @param userId 账号存在时传它的 id（能看出"是谁的账号被试了"）；账号不存在时传 null——
     *               此时操作人记 0（系统），而**姓名列写尝试用的那个用户名**，那正是要看的东西
     * @param action {@link com.bluemalic.repair.common.AuditAction#LOGIN_FAILED} 或 {@code LOGIN_DISABLED}
     * @param detail 失败原因（"口令错误" / "账号不存在" / "账号已停用"），**绝不带口令原文**
     *
     * <p>同样**绝不抛异常**（理由见 {@link #recordLoginSuccess}）。
     */
    void recordLoginFailure(long tenantId, Long userId, String username, String action, String detail);

    /**
     * 审计日志分页（本租户，时间倒序）。
     *
     * @param action          动作码精确筛选，null = 全部
     * @param operatorKeyword 操作人姓名/账号模糊筛选
     * @param startDate       起始日期（含），null = 不限
     * @param endDate         结束日期（含），null = 不限
     * @param includeLogin    是否包含登录类事件。**默认 false**：这个页面的主查询是"谁改了东西"，
     *                        而登录记录会占绝大多数（`docs/01` §4.4）。显式传了 {@code action} 时
     *                        这个参数不起作用——那时按动作筛就是了
     */
    PageResult<AuditLogVO> page(long pageNum, long pageSize, String action, String operatorKeyword,
                                LocalDate startDate, LocalDate endDate, boolean includeLogin);
}
