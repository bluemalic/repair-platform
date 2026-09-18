package com.bluemalic.repair.service;

import com.bluemalic.repair.dto.LoginDTO;
import com.bluemalic.repair.dto.PasswordChangeDTO;
import com.bluemalic.repair.vo.CurrentUserVO;
import com.bluemalic.repair.vo.LoginVO;

public interface AuthService {

    /** 账号密码登录，成功返回 token 与用户基本信息。 */
    LoginVO login(LoginDTO dto);

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
