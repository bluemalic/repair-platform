package com.bluemalic.repair.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 重置口令入参（管理员给某个账号设一个新口令）。
 *
 * <p>单独一个 DTO 而不是复用 {@code StudentUpdateDTO}：那个 DTO 的 {@code status} 是必填的，
 * 只想重置口令却必须传一个状态，会让人误以为"重置会把状态也改掉"。
 */
@Data
@Schema(description = "重置口令入参")
public class StudentPasswordDTO {

    @Schema(description = "新口令，8-32 位")
    @NotBlank(message = "口令不能为空")
    @Size(min = 8, max = 32, message = "口令需为 8-32 位")
    private String password;
}
