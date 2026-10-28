package com.bluemalic.repair;

import com.bluemalic.repair.config.StorageProperties;
import com.bluemalic.repair.entity.SysUser;
import com.bluemalic.repair.entity.SysUserRole;
import com.bluemalic.repair.mapper.SysUserMapper;
import com.bluemalic.repair.mapper.SysUserRoleMapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.client.RestClient;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;

/**
 * 真实上传（需要 MinIO 在跑：`docker compose up -d minio`；CI 里是 service 容器）。
 *
 * <p>为什么要单独一条而不并进 {@code FileUploadTest}：**对象存储是外部依赖，事务回滚不了它**。
 * 校验层的用例不碰 MinIO（谁都能跑），而这条要真的写一个对象、并验证"返回的 URL 匿名就能打开"——
 * 后者是对"桶设为匿名可读"这个决定的实测，光看代码是验不了的。
 *
 * <p>对象在 {@code @AfterEach} 里删掉：不删的话每跑一次测试就往桶里堆一张图。
 */
@IntegrationTest
class FileUploadStorageTest {

    private static final String PASSWORD = "Test@123456";
    private static final long ROLE_STUDENT = 1L;
    /** PNG 文件头 + 少量填充：校验只读前 12 字节，所以不必造完整可解码的图片。 */
    private static final byte[] PNG_BYTES = {
            (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D,
            1, 2, 3, 4, 5, 6, 7, 8};

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SysUserMapper sysUserMapper;

    @Autowired
    private SysUserRoleMapper sysUserRoleMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private StorageProperties storage;

    private final List<String> uploadedKeys = new ArrayList<>();

    @AfterEach
    void cleanUpObjects() {
        if (uploadedKeys.isEmpty()) {
            return;
        }
        try (S3Client client = S3Client.builder()
                .endpointOverride(URI.create(storage.getEndpoint()))
                .forcePathStyle(true)
                .region(Region.of(storage.getRegion()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(storage.getAccessKey(), storage.getSecretKey())))
                .build()) {
            uploadedKeys.forEach(key -> client.deleteObject(builder -> builder
                    .bucket(storage.getBucket()).key(key)));
        }
    }

    @Test
    void uploadsImageAndUrlIsPubliclyReadable() throws Exception {
        String token = givenToken("test-upload-real");

        MvcResult result = mockMvc.perform(multipart("/api/files/upload")
                        .file(new MockMultipartFile("file", "photo.png", "image/png", PNG_BYTES))
                        .header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();

        JsonNode data = objectMapper
                .readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data");
        String objectKey = data.path("objectKey").asText();
        String url = data.path("url").asText();
        uploadedKeys.add(objectKey);

        // 对象键按"租户/日期/uuid.扩展名"分目录（docs/03 §7.3）
        assertThat(objectKey).startsWith("1/" + LocalDate.now() + "/").endsWith(".png");
        assertThat(objectKey).doesNotContain("photo"); // 不使用任何用户提供的文件名
        assertThat(url).isEqualTo(storage.getPublicBaseUrl() + "/" + storage.getBucket() + "/" + objectKey);

        // 最关键的一条：这个 URL **匿名（不带 token）**就能取到图，且类型正确。
        // 图片 URL 要长期存进工单、直接给 <img> 用，所以桶是匿名可读的；这里实测这个决定成立。
        RestClient client = RestClient.create();
        byte[] fetched = client.get().uri(url).retrieve().toEntity(byte[].class).getBody();
        assertThat(fetched).isEqualTo(PNG_BYTES);
        assertThat(client.get().uri(url).retrieve().toEntity(byte[].class)
                .getHeaders().getContentType().toString()).isEqualTo("image/png");
    }

    private String givenToken(String username) throws Exception {
        SysUser user = new SysUser();
        user.setTenantId(1L);
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(PASSWORD));
        user.setRealName(username);
        user.setUserType(1);
        user.setStatus(1);
        sysUserMapper.insert(user);

        SysUserRole link = new SysUserRole();
        link.setUserId(user.getId());
        link.setRoleId(ROLE_STUDENT);
        sysUserRoleMapper.insert(link);

        MvcResult result = mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "tenantCode", "gdou", "username", username, "password", PASSWORD))))
                .andExpect(jsonPath("$.code").value(0))
                .andReturn();
        return objectMapper.readTree(result.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .path("data").path("tokenValue").asText();
    }
}
