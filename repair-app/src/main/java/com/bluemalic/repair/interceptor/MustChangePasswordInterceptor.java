package com.bluemalic.repair.interceptor;

import cn.dev33.satoken.stp.StpUtil;
import com.bluemalic.repair.common.BizException;
import com.bluemalic.repair.common.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 首次登录强制改密的拦截。
 *
 * <p><b>为什么必须有这一道</b>：管理员批量建学生账号时，初始口令只能是**统一**的（口令要写给
 * 一个班，一人一个反而没人记得住），而学号在班级里是公开信息——统一口令意味着同学之间可以
 * 互相登录。所以它必须是一次性的：不改掉就别用其它功能。
 *
 * <p>前端的"登录后跳改密页"只是引导，绕过它照样能直接调接口。要真的成立，服务端得也拦一道。
 *
 * <p><b>标记读的是 Sa-Token Session，不是每请求查一次库</b>：登录时把
 * {@code must_change_password} 写进 Session（那里本来就要存租户，多一个布尔值不增加成本），
 * 改密成功后该账号的所有会话都被注销，标记随之消失——不需要单独清理。
 *
 * <p>白名单只放行 {@code /api/auth/**}：改密、登出、看当前用户这三个必须能用，
 * 否则用户会卡在"要改密才能用、但要改密却调不动接口"的死循环里。
 */
@Slf4j
@Component
public class MustChangePasswordInterceptor implements HandlerInterceptor {

    /** Session 里存"是否待改密"的键。登录时写入，改密后随会话注销一起消失。 */
    public static final String SESSION_KEY = "mustChangePassword";

    /** 待改密时仍必须能访问的路径前缀。 */
    private static final String AUTH_PATH_PREFIX = "/api/auth/";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }
        if (request.getRequestURI().startsWith(AUTH_PATH_PREFIX)) {
            return true;
        }
        Object pending;
        try {
            pending = StpUtil.getSession(false) == null ? null : StpUtil.getSession().get(SESSION_KEY);
        } catch (Exception e) {
            // 没有登录态时不该由这里下判断——交给 Sa-Token 的登录校验去返回 10002
            return true;
        }
        if (Boolean.TRUE.equals(pending)) {
            // 复用 10003（无权限访问该资源），文案说清原因：不新造错误码，前端也已有通用提示
            log.info("待改密账号访问被拦下 uri={}", request.getRequestURI());
            throw new BizException(ErrorCode.NO_PERMISSION, "请先修改初始口令");
        }
        return true;
    }
}
