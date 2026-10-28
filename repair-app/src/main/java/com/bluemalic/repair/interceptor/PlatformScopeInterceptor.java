package com.bluemalic.repair.interceptor;

import cn.dev33.satoken.stp.StpUtil;
import com.bluemalic.repair.common.BizException;
import com.bluemalic.repair.common.ErrorCode;
import com.bluemalic.repair.common.UserType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 平台域的收口：**平台运营账号只能访问 {@code /api/platform/**} 与 {@code /api/auth/**}**。
 *
 * <p><b>为什么必须有这一道，而不是"给平台角色只授一个权限码就够了"</b>：权限码是"有注解才生效"
 * 的白名单机制，而项目里有三个接口**完全没有权限码**（{@code /api/categories}、
 * {@code /api/files/upload}、{@code /api/tickets/by-code/{code}}，它们三个端都要用，
 * 一直只要求"登录"）。只授一个码挡不住它们——平台账号照样能调通、能在对象存储里以租户 0 的
 * 前缀真实写入文件。
 *
 * <p>所以这里换成正向陈述：**平台账号被关在平台域里**。它不依赖任何接口记得加注解，
 * 以后新增的租户接口自动被挡住。代价是平台运营看不到任何学校的业务数据——这本来就是设计意图
 * （ADR-012），不是副作用。
 *
 * <p>与 {@code MustChangePasswordInterceptor} 同一套失败姿态：读不到 Session 就放行。
 * 这样是安全的——order 0 的 {@code SaTokenConfigure} 已经对 {@code /api/**} 要求过有效登录，
 * 走到这里必然有登录态；真正读不到只会是"这次请求根本没有会话"（比如浏览器直接开 doc.html），
 * 那时更该由登录校验去回 10002，而不是由这里回 10003。
 */
@Slf4j
@Component
public class PlatformScopeInterceptor implements HandlerInterceptor {

    /**
     * Session 里存"用户类型"的键。登录时写入（与"待改密"标记同一个位置）。
     *
     * <p>存的是 {@code sys_user.user_type}，不是布尔标记：将来要在服务端按类型分支时不用再加键。
     * 判断时用 {@code Number} 而不是 {@code Integer}——Session 经 JSON 往返后，
     * 数字可能回来是 Integer 也可能是 Long，写死 Integer 会变成"校验静默失效"。
     */
    public static final String SESSION_USER_TYPE_KEY = "userType";

    /** 平台域：平台账号能用的路径。 */
    private static final String PLATFORM_PATH_PREFIX = "/api/platform/";

    /** 自助路径（改密 / 登出 / 看当前用户）：平台账号也需要，否则它改不了自己的初始口令。 */
    private static final String AUTH_PATH_PREFIX = "/api/auth/";

    /** 只管业务接口；{@code /doc.html}、{@code /actuator/health} 不归这里管。 */
    private static final String API_PATH_PREFIX = "/api/";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }
        String uri = request.getRequestURI();
        if (!uri.startsWith(API_PATH_PREFIX)
                || uri.startsWith(PLATFORM_PATH_PREFIX)
                || uri.startsWith(AUTH_PATH_PREFIX)) {
            return true;
        }
        if (isPlatformAccount()) {
            // 复用 10003（无权限访问该资源），文案说清原因：不新造错误码
            log.info("平台运营账号访问租户域被拦下 uri={}", uri);
            throw new BizException(ErrorCode.NO_PERMISSION, "平台运营账号只能访问平台运营接口");
        }
        return true;
    }

    private boolean isPlatformAccount() {
        try {
            var session = StpUtil.getSession(false);
            Object userType = session == null ? null : session.get(SESSION_USER_TYPE_KEY);
            return userType instanceof Number n && n.intValue() == UserType.PLATFORM.getCode();
        } catch (Exception e) {
            return false;
        }
    }
}
