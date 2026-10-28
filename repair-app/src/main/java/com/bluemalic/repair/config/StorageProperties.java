package com.bluemalic.repair.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

/**
 * 对象存储（MinIO，S3 协议）配置。与 {@code TimeoutRule} / {@code RateLimitRule} 同一套路：
 * 默认值写在配置类里，环境变量可覆盖。
 *
 * <p><b>endpoint 与 publicBaseUrl 为什么是两个</b>：应用连 MinIO 走的是**容器网络**里的地址
 * （`http://minio:9000`），而返回给浏览器、要长期存进工单的 URL 必须是**用户能访问到的地址**
 * （本机是 `http://127.0.0.1:9000`，线上是域名）。这两件事在部署时天然不同，合成一个键
 * 就会出现"内网地址存进了数据库、前端打不开"——所以分开，且 publicBaseUrl 不填时退回 endpoint
 * （本地开发只配一个键就能跑）。
 *
 * <ul>
 *   <li>{@code MINIO_ENDPOINT}：应用访问 MinIO 的地址</li>
 *   <li>{@code MINIO_PUBLIC_BASE_URL}：返回给前端的图片 URL 前缀</li>
 *   <li>{@code MINIO_ACCESS_KEY} / {@code MINIO_SECRET_KEY}：密钥，只从环境变量读</li>
 *   <li>{@code MINIO_BUCKET}：桶名（首次上传时自动创建）</li>
 *   <li>{@code STORAGE_MAX_IMAGE_BYTES}：单张图片上限（默认 5MB，docs/03 §7.3）</li>
 * </ul>
 */
@Getter
@Component
public class StorageProperties {

    private final String endpoint;
    private final String publicBaseUrl;
    private final String accessKey;
    private final String secretKey;
    private final String bucket;
    private final String region;
    private final long maxImageBytes;

    public StorageProperties(@Value("${MINIO_ENDPOINT:http://127.0.0.1:9000}") String endpoint,
                             @Value("${MINIO_PUBLIC_BASE_URL:}") String publicBaseUrl,
                             @Value("${MINIO_ACCESS_KEY:}") String accessKey,
                             @Value("${MINIO_SECRET_KEY:}") String secretKey,
                             @Value("${MINIO_BUCKET:repair}") String bucket,
                             // MinIO 不校验 region，但 SDK 要求必填
                             @Value("${MINIO_REGION:us-east-1}") String region,
                             @Value("${STORAGE_MAX_IMAGE_BYTES:5242880}") long maxImageBytes) {
        this.endpoint = trimTrailingSlash(endpoint);
        this.publicBaseUrl = trimTrailingSlash(
                StringUtils.hasText(publicBaseUrl) ? publicBaseUrl : endpoint);
        this.accessKey = accessKey;
        this.secretKey = secretKey;
        this.bucket = bucket;
        this.region = region;
        this.maxImageBytes = maxImageBytes;
    }

    /** 密钥是否齐备。缺了不阻止启动（启动不依赖 MinIO），但上传会明确报 50003。 */
    public boolean configured() {
        return StringUtils.hasText(accessKey) && StringUtils.hasText(secretKey);
    }

    /** 上限的中文文案，用在错误提示里（阈值被环境变量改过时也不会说谎）。 */
    public String maxImageSizeText() {
        return (maxImageBytes / 1024 / 1024) + " MB";
    }

    private String trimTrailingSlash(String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
}
