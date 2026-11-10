package com.bluemalic.repair.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 当前登录用户的出参。
 *
 * <p>带上角色码与权限码，是为了让前端能做**动态路由和按钮级控制**：
 * 菜单该不该显示、按钮该不该渲染，都按 permissions 判断，而不是前端硬编码角色。
 */
@Data
@Schema(description = "当前登录用户")
public class CurrentUserVO {

    private Long userId;

    private String username;

    private String realName;

    @Schema(description = "1学生 2维修工 3后勤管理")
    private Integer userType;

    @Schema(description = "角色码，如 STUDENT / WORKER / ADMIN")
    private List<String> roles;

    @Schema(description = "权限码，如 ticket:dispatch")
    private List<String> permissions;
}
