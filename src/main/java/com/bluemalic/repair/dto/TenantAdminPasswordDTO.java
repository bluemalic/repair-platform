package com.bluemalic.repair.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 平台运营重置某个租户管理员的口令。
 *
 * <p>这是"后勤管理员忘记口令"在本项目里的**唯一出路**：项目明确不做短信 / 邮件
 * （见 docs/01 §4 明确不做），没有自助找回的通道，只能由平台侧核验身份后代为重置。
 * 重置出来的口令同样是一次性的——账号回到"首次登录必须改密"。
 */
@Data
@Schema(description = "重置租户管理员口令入参")
public class TenantAdminPasswordDTO {

    @Schema(description = "新口令，8-32 位；重置后首次登录必须改")
    @NotBlank(message = "口令不能为空")
    @Size(min = 8, max = 32, message = "口令需为 8-32 位")
    private String password;
}
