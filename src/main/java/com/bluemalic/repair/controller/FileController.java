package com.bluemalic.repair.controller;

import com.bluemalic.repair.common.Result;
import com.bluemalic.repair.interceptor.RateLimit;
import com.bluemalic.repair.service.FileStorageService;
import com.bluemalic.repair.vo.FileUploadVO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 文件上传（docs/03 §7.3）。各端共用：学生传现场图、维修工传维修后图，都是这一个接口。
 *
 * <p>只需要登录，没有功能权限码——能不能给某张工单附图，由工单本身的接口鉴权决定。
 */
@Tag(name = "文件", description = "图片上传（工单现场图与维修后图）")
@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileController {

    private final FileStorageService fileStorageService;

    /**
     * 加 {@code @RateLimit}：它不属于"可枚举短凭证"，但**是唯一会在服务端产生存储消耗**的接口，
     * 被脚本刷起来的代价比查询大得多（AGENTS §7）。
     */
    @Operation(summary = "上传图片",
            description = "multipart 表单字段名 `file`；只接受 jpg / png / webp（按文件头判断真实类型），"
                    + "单张不超过 5MB；返回可直接访问的 url 与 objectKey")
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RateLimit
    public Result<FileUploadVO> upload(
            @Parameter(description = "图片文件") @RequestPart("file") MultipartFile file) {
        return Result.ok(fileStorageService.upload(file));
    }
}
