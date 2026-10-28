package com.bluemalic.repair.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.util.List;

/**
 * 批量导入的结果。
 *
 * <p>**"跳过"不是错误**：学校补录学生时必然会重复导入同一批名单（新名单里带着上次已导的人），
 * 报错就没法用了。所以重复学号静默跳过、并在结果里列出来让管理员核对。
 */
@Data
@Schema(description = "批量导入结果")
public class StudentImportVO {

    @Schema(description = "新建的账号数")
    private int created;

    @Schema(description = "跳过的条数（学号已存在）")
    private int skipped;

    @Schema(description = "被跳过的学号（便于核对是不是导错了名单）")
    private List<String> skippedUsernames;

    @Schema(description = "名单里被忽略的空行/格式不对的条数")
    private int ignored;
}
