package com.bluemalic.repair.service;

import com.bluemalic.repair.dto.TenantAdminCreateDTO;
import com.bluemalic.repair.dto.TenantCreateDTO;
import com.bluemalic.repair.dto.TenantUpdateDTO;
import com.bluemalic.repair.vo.PageResult;
import com.bluemalic.repair.vo.TenantAdminVO;
import com.bluemalic.repair.vo.TenantVO;

import java.util.List;

/**
 * 平台运营：开通 / 维护租户，以及维护每个租户的后勤管理员。
 *
 * <p><b>这个 Service 与其它 Service 最大的不同：它跨租户</b>。别的 Service 都从
 * {@code CurrentTenantService} 取"当前登录者的租户"，这里租户 ID 是**入参**——因为平台运营
 * 本来就不属于任何一个租户（它的 {@code tenant_id} 是 0）。
 *
 * <p>所以"租户边界"在这层必须靠参数校验守住，不能靠拦截器：
 * <ul>
 *   <li>每个方法第一件事是 {@link #requireTenant}——它同时排除了 {@code id = 0}（平台自身），
 *       否则平台就能"停用自己"，把自己锁在门外</li>
 *   <li>改账号的语句一律走 {@code AccountService}，那边每条语句都显式带 {@code tenant_id}</li>
 * </ul>
 *
 * <p>平台**看不到任何租户的业务数据**（工单、学生、统计），这一点由
 * {@code PlatformScopeInterceptor} 在拦截器层保证，不靠这里自觉。
 */
public interface TenantProvisionService {

    /** 租户列表。**不含平台自身（id = 0）**——它不是学校。 */
    PageResult<TenantVO> page(long pageNum, long pageSize, Integer status, String keyword);

    /** 开通一所学校：建租户 + 建它的第一个后勤管理员（同一个事务）。 */
    TenantVO provision(TenantCreateDTO dto);

    /** 改名称 / 联系人 / 电话。不含编码与状态，理由见 {@code TenantUpdateDTO}。 */
    void update(long tenantId, TenantUpdateDTO dto);

    /** 启用 / 停用。**停用会把该租户所有在线用户踢下线**，之后他们也无法再登录。 */
    void changeStatus(long tenantId, int status);

    /** 该租户的后勤管理员列表。 */
    List<TenantAdminVO> admins(long tenantId);

    /** 给已有租户新增一个后勤管理员（不新建学校）。 */
    TenantAdminVO addAdmin(long tenantId, TenantAdminCreateDTO dto);

    /**
     * 重置某个租户管理员的口令，并让他下次登录必须改密。
     *
     * <p>这是"后勤管理员忘记口令"的唯一出路：项目不做短信 / 邮件，没有自助找回通道。
     */
    void resetAdminPassword(long tenantId, long userId, String rawPassword);
}
