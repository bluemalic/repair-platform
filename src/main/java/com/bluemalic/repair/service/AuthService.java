package com.bluemalic.repair.service;

import com.bluemalic.repair.dto.LoginDTO;
import com.bluemalic.repair.entity.SysUser;
import com.bluemalic.repair.entity.Tenant;
import com.bluemalic.repair.dto.PasswordChangeDTO;
import com.bluemalic.repair.vo.CurrentUserVO;
import com.bluemalic.repair.vo.LoginVO;

public interface AuthService {

    /** 账号密码登录，成功返回 token 与用户基本信息。 */
    LoginVO login(LoginDTO dto);

    /**
     * 把"已经验证过身份"的用户变成一次登录（阶段 7 · ADR-022）。
     *
     * <p>密码登录与统一认证（SSO）共用这一步：发 token、绑租户、写会话标记、记登录审计。
     * **调用方负责证明身份**，并保证已经在**这所学校自己的库**的路由键下（审计要写进它的库）。
     */
    LoginVO completeLogin(Tenant tenant, SysUser user);

    /** 注销当前 token。 */
    void logout();

    /**
     * 自助改密：校验当前密码后写入新密码，并让该账号的<b>所有</b>会话失效（含发起改密的这一次）。
     * 调用方（前端）在拿到成功后必须清本地登录态并跳登录页。
     */
    void changePassword(PasswordChangeDTO dto);

    /** 当前登录用户，含角色码与权限码，供前端做动态路由和按钮控制。 */
    CurrentUserVO currentUser();
}
