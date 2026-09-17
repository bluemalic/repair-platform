package com.bluemalic.repair.service.impl;

import com.bluemalic.repair.common.BizException;
import com.bluemalic.repair.common.ErrorCode;
import com.bluemalic.repair.common.ImageTypeDetector;
import com.bluemalic.repair.config.StorageProperties;
import com.bluemalic.repair.service.CurrentTenantService;
import com.bluemalic.repair.service.FileStorageService;
import com.bluemalic.repair.vo.FileUploadVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 图片上传。三件事按顺序做，缺一件都不行：
 *
 * <ol>
 *   <li><b>大小</b>：按配置的业务上限（默认 5MB）判断，超了返回 {@code 50002}</li>
 *   <li><b>类型</b>：**按文件头魔数判真实类型**，不认后缀与客户端声明的 Content-Type
 *       （改名的 .html/.svg 若被当页面打开就是存储型 XSS）；不在白名单返回 {@code 50001}</li>
 *   <li><b>落库前先落存储</b>：对象名用 uuid（不含任何用户输入）、Content-Type 由服务端写死，
 *       失败返回 {@code 50003}</li>
 * </ol>
 *
 * <p><b>启动不依赖 MinIO</b>（docs/01 §5 降级策略）：S3 客户端与桶都是**第一次上传时**才建，
 * 所以 MinIO 没起时应用照常启动，只是上传报 50003——否则一个本地没开 MinIO 的开发者连后端起不来。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FileStorageServiceImpl implements FileStorageService {

    private final StorageProperties storage;
    private final CurrentTenantService currentTenantService;

    /** 懒建：见类注释（启动不依赖 MinIO）。 */
    private volatile S3Client s3Client;

    /** 桶只需确认一次；首次上传时幂等创建。 */
    private final AtomicBoolean bucketEnsured = new AtomicBoolean();

    @Override
    public FileUploadVO upload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.PARAM_INVALID, "请选择要上传的文件");
        }
        if (file.getSize() > storage.getMaxImageBytes()) {
            throw new BizException(ErrorCode.FILE_SIZE_EXCEEDED,
                    "图片不能超过 " + storage.maxImageSizeText());
        }

        ImageTypeDetector.ImageType type = detectType(file);
        long tenantId = currentTenantService.requireTenantId();
        String objectKey = objectKey(tenantId, type.extension());

        try {
            ensureBucket();
            client().putObject(builder -> builder
                            .bucket(storage.getBucket())
                            .key(objectKey)
                            // 服务端写死类型，不采信客户端传来的 Content-Type
                            .contentType(type.contentType())
                            .contentLength(file.getSize())
                            .build(),
                    RequestBody.fromInputStream(file.getInputStream(), file.getSize()));
        } catch (IOException | S3Exception e) {
            // 只记对象的定位信息与原因，不把完整堆栈刷给上传接口（失败率可能很高）
            log.warn("图片上传失败 objectKey={} 原因={}", objectKey, e.toString());
            throw new BizException(ErrorCode.FILE_UPLOAD_FAILED);
        }

        log.info("上传图片成功 objectKey={} size={} type={} tenantId={}",
                objectKey, file.getSize(), type.extension(), tenantId);
        return new FileUploadVO(publicUrl(objectKey), objectKey);
    }

    private ImageTypeDetector.ImageType detectType(MultipartFile file) {
        byte[] head;
        try (InputStream in = file.getInputStream()) {
            head = in.readNBytes(ImageTypeDetector.HEAD_BYTES);
        } catch (IOException e) {
            log.warn("读取上传文件失败: {}", e.toString());
            throw new BizException(ErrorCode.FILE_UPLOAD_FAILED);
        }
        ImageTypeDetector.ImageType type = ImageTypeDetector.detect(head);
        if (type == null) {
            throw new BizException(ErrorCode.FILE_TYPE_UNSUPPORTED, "只允许 jpg / png / webp 图片");
        }
        return type;
    }

    /** 对象键：{@code 租户/日期/uuid.扩展名}（docs/03 §7.3）。租户前缀让"清某个学校的数据"变得可执行。 */
    private String objectKey(long tenantId, String extension) {
        return tenantId + "/" + LocalDate.now() + "/"
                + UUID.randomUUID().toString().replace("-", "") + "." + extension;
    }

    private String publicUrl(String objectKey) {
        return storage.getPublicBaseUrl() + "/" + storage.getBucket() + "/" + objectKey;
    }

    private void ensureBucket() {
        if (bucketEnsured.get()) {
            return;
        }
        synchronized (this) {
            if (bucketEnsured.get()) {
                return;
            }
            try {
                client().headBucket(builder -> builder.bucket(storage.getBucket()));
            } catch (S3Exception e) {
                if (e.statusCode() != 404) {
                    throw e;
                }
                client().createBucket(builder -> builder.bucket(storage.getBucket()));
                // 图片 URL 要长期存进工单并直接给 <img> 用，所以桶设为允许匿名读：
                // 预签名链接会过期（存进工单的 URL 到期就全打不开了），而对象名是 uuid、不可枚举。
                // 代价如实记录：拿到完整 URL 的人就能看这张图（学校维修照片，不是机密材料）。
                client().putBucketPolicy(builder -> builder
                        .bucket(storage.getBucket())
                        .policy(publicReadPolicy()));
                log.info("已创建对象存储桶并设为匿名可读 bucket={}", storage.getBucket());
            }
            bucketEnsured.set(true);
        }
    }

    private String publicReadPolicy() {
        return """
                {"Version":"2012-10-17","Statement":[{"Effect":"Allow","Principal":{"AWS":["*"]},
                "Action":["s3:GetObject"],"Resource":["arn:aws:s3:::%s/*"]}]}
                """.formatted(storage.getBucket());
    }

    private S3Client client() {
        if (!storage.configured()) {
            log.warn("对象存储未配置（MINIO_ACCESS_KEY / MINIO_SECRET_KEY 为空）");
            throw new BizException(ErrorCode.FILE_UPLOAD_FAILED, "对象存储未配置，请联系管理员");
        }
        S3Client local = s3Client;
        if (local == null) {
            synchronized (this) {
                local = s3Client;
                if (local == null) {
                    local = S3Client.builder()
                            .endpointOverride(URI.create(storage.getEndpoint()))
                            // MinIO 必须用 path-style：SDK 默认的 virtual-host 形式（bucket.主机名）
                            // 在 MinIO 上解析不到，表现为"连接超时/找不到主机"这种误导性错误
                            .forcePathStyle(true)
                            .region(Region.of(storage.getRegion()))
                            .credentialsProvider(StaticCredentialsProvider.create(
                                    AwsBasicCredentials.create(storage.getAccessKey(), storage.getSecretKey())))
                            .build();
                    s3Client = local;
                }
            }
        }
        return local;
    }
}
