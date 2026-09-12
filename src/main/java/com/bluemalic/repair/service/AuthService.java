package com.bluemalic.repair.service;

import com.bluemalic.repair.dto.LoginDTO;
import com.bluemalic.repair.vo.CurrentUserVO;
import com.bluemalic.repair.vo.LoginVO;

public interface AuthService {

    /** 账号密码登录，成功返回 token 与用户基本信息。 */
    LoginVO login(LoginDTO dto);

    /** 注销当前 token。 */
    void logout();

    /** 当前登录用户，含角色码与权限码，供前端做动态路由和按钮控制。 */
    CurrentUserVO currentUser();
}
