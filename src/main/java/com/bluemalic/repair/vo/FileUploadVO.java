package com.bluemalic.repair.vo;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 上传结果（docs/03 §7.3）。
 *
 * <p>两个字段都给：{@code url} 供前端直接展示，{@code objectKey} 是对象存储里的定位符
 * （排查"图丢了"时要靠它去存储里找，URL 换了域名也还能对上）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Schema(description = "图片上传结果")
public class FileUploadVO {

    @Schema(description = "可直接访问的图片 URL")
    private String url;

    @Schema(description = "对象存储中的键，形如 1/2026-09-17/8f3c….jpg")
    private String objectKey;
}
