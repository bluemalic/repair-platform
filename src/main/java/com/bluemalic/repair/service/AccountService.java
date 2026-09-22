package com.bluemalic.repair.service;

import com.bluemalic.repair.entity.SysUser;

/**
 * 账号的公共操作：建号、改资料、启停、重置口令。
 *
 * <p><b>为什么要抽这一层</b>：维修工管理和学生管理除了"负责楼栋"之外几乎完全一样——
 * 都是"写 sys_user + 写 sys_user_role 关联、用户名冲突报已存在、停用要踢下线"。
 * 不抽的话就是第二份几乎相同的账号代码，以后改密码策略（比如加长度校验、加口令复杂度）
 * 要改两处，而"两处不一致"正是这个项目一直在防的事。
 *
 * <p><b>三条不变量收在这里，出不去</b>：
 * <ol>
 *   <li><b>建号必写角色关联。</b>只写 sys_user 的账号能登录、但权限码为空，每个接口都 403——
 *       这个坑很难从现象倒推到"少插了一张关联表"</li>
 *   <li><b>停用必踢下线。</b>登录态在 Redis，不随 {@code sys_user.status} 变化，
 *       只写库的话他手上那个 7 天有效的 token 照样能用</li>
 *   <li><b>每条语句都显式带 tenant_id。</b>{@code sys_user} 不在数据权限拦截器的范围内
 *       （拦截器只管 ticket / notification，见 AGENTS §5.6），所以这里每个方法都收 tenantId。
 *       按主键更新时它看着多余，但少了它就是"将来某处漏调 require() 也照样改得了别家租户的账号"</li>
 * </ol>
 */
public interface AccountService {

    /**
     * 新建账号（含角色关联）。
     *
     * @param spec 见 {@link NewAccount}；{@code usernameLabel} 用于冲突文案（"工号"/"学号"）
     */
    SysUser create(NewAccount spec);

    /** 改基础资料。不含密码与状态——那两件事各有单独的入口。 */
    void updateProfile(long userId, long tenantId, String realName, String phone);

    /** 启用 / 停用。**停用时踢下线**；对从未登录过的账号不会抛异常。 */
    void changeStatus(long userId, long tenantId, int status, String accountLabel);

    /** 重置口令，并设置"下次登录是否必须改密"。 */
    void resetPassword(long userId, long tenantId, String rawPassword, boolean mustChangePassword);

    /** 清除"需强制改密"标记（改密成功后调用）。 */
    void clearMustChangePassword(long userId, long tenantId);

    /**
     * 取本租户、本类型的账号；不存在 / 不是这个类型 / 是别家租户的，对外一律说"不存在"
     * （不透露"这个 id 存在但不属于你"）。
     */
    SysUser require(long userId, long tenantId, int userType, String accountLabel);

    /**
     * 把账号的角色重设为指定角色的**唯一一条关联**（先删旧关联再写）。
     *
     * <p>给"重建"类场景用（演示重置每天要把账号恢复成初始样子）。日常的建号走
     * {@link #create}——它写的是唯一的关联，不需要重设。
     */
    void resetRole(long userId, String roleCode);

    /** 用户名是否已存在。批量导入用它跳过重复项；并发下的真正兜底仍是唯一键。 */
    boolean usernameExists(long tenantId, String username);

    /**
     * 建号入参。
     *
     * @param userType           取值见 {@code UserType}，由调用方（服务端）指定
     * @param roleCode           对应 {@code sys_role.code}，由调用方按 userType 指定
     * @param mustChangePassword true 表示这是管理员设的初始口令，首次登录必须改
     * @param usernameLabel      冲突文案里对用户名的称呼，如"工号"、"学号"
     */
    record NewAccount(long tenantId, String username, String rawPassword, String realName,
                      String phone, int userType, String roleCode, boolean mustChangePassword,
                      String usernameLabel) {
    }
}
