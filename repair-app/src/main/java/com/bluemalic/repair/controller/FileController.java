package com.bluemalic.repair.controller;

import com.bluemalic.repair.common.Result;
import com.bluemalic.repair.interceptor.RateLimit;
import com.bluemalic.repair.service.FileStorageService;
import com.bluemalic.repair.service.CurrentTenantService;
import com.bluemalic.repair.mapper.TicketMapper;
import com.bluemalic.repair.entity.Ticket;
import com.bluemalic.repair.vo.FileUploadVO;
import com.bluemalic.repair.common.BizException;
import com.bluemalic.repair.common.ErrorCode;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.services.s3.model.S3Exception;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.Valid;

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
    private final CurrentTenantService currentTenantService;
    private final TicketMapper ticketMapper;

    /**
     * 加 {@code @RateLimit}：它不属于"可枚举短凭证"，但**是唯一会在服务端产生存储消耗**的接口，
     * 被脚本刷起来的代价比查询大得多（AGENTS §7）。
     */
    @Operation(summary = "上传图片",
            description = "multipart 表单字段名 `file`；只接受 jpg / png / webp（按文件头判断真实类型），"
                    + "单张不超过 5MB；返回短期应用访问地址与 objectKey")
    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RateLimit(key = "upload")
    public Result<FileUploadVO> upload(
            @Parameter(description = "图片文件") @RequestPart("file") MultipartFile file) {
        FileUploadVO uploaded = fileStorageService.upload(file);
        uploaded.setUrl(ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString() + uploaded.getUrl());
        return Result.ok(uploaded);
    }

    @Operation(summary = "获取工单图片短期访问地址",
            description = "仅当图片对象键已关联到当前用户有权读取的工单时签发，链接有效期 10 分钟")
    @PostMapping("/image-url")
    public Result<ImageUrlVO> imageUrl(@Valid @RequestBody ImageUrlRequest body) {
        long tenantId = currentTenantService.requireTenantId();
        String objectKey = fileStorageService.objectKeyForReference(body.reference(), tenantId);
        if (objectKey == null || !isLinkedToVisibleTicket(objectKey)) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "图片不存在或无权访问");
        }
        String relative = fileStorageService.signedUrl(objectKey, tenantId);
        String url = ServletUriComponentsBuilder.fromCurrentContextPath().build().toUriString() + relative;
        return Result.ok(new ImageUrlVO(url));
    }

    @Operation(summary = "读取短期签名工单图片", hidden = true)
    @GetMapping("/image")
    public ResponseEntity<StreamingResponseBody> image(
            @RequestParam long tenantId,
            @RequestParam String key,
            @RequestParam long expires,
            @RequestParam String signature) {
        String objectKey = fileStorageService.objectKeyForReference(key, tenantId);
        if (objectKey == null || !fileStorageService.verifySignedUrl(objectKey, tenantId, expires, signature)) {
            throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "图片链接无效或已过期");
        }
        FileStorageService.StoredImage stored;
        try {
            stored = fileStorageService.openImage(objectKey);
        } catch (S3Exception e) {
            if (e.statusCode() == 404) {
                throw new BizException(ErrorCode.RESOURCE_NOT_FOUND, "图片不存在或已失效");
            }
            throw new BizException(ErrorCode.FILE_UPLOAD_FAILED, "图片读取失败");
        }
        StreamingResponseBody body = output -> {
            try (stored) {
                stored.stream().transferTo(output);
            }
        };
        ResponseEntity.BodyBuilder response = ResponseEntity.ok()
                .header(HttpHeaders.CACHE_CONTROL, "private, max-age=300")
                .header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_TYPE, stored.contentType() == null ? "application/octet-stream" : stored.contentType());
        if (stored.contentLength() != null) {
            response.contentLength(stored.contentLength());
        }
        return response.body(body);
    }

    private boolean isLinkedToVisibleTicket(String reference) {
        // ticket Mapper 上的数据权限拦截器会把当前租户与角色可见范围注入查询。
        // 使用普通 selectList（不是 selectOne/cursor）以确保插件执行。
        return !ticketMapper.selectList(Wrappers.<Ticket>lambdaQuery()
                        .apply("(JSON_CONTAINS(images, JSON_QUOTE({0})) OR JSON_CONTAINS(result_images, JSON_QUOTE({0})))",
                                reference)
                        .last("LIMIT 1"))
                .isEmpty();
    }

    public record ImageUrlRequest(@NotBlank String reference) {}

    public record ImageUrlVO(String url) {}
}
