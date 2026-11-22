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
import org.springframework.web.client.HttpClientErrorException;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 真实上传（需要 MinIO 在跑：`docker compose up -d minio`；CI 里是 service 容器）。
 *
 * <p>为什么要单独一条而不并进 {@code FileUploadTest}：**对象存储是外部依赖，事务回滚不了它**。
 * 校验层的用例不碰 MinIO（谁都能跑），而这条要真的写一个对象，并验证桶保持私有、
 * 只有应用签发的短期地址才能读取——后者光看代码是验不了的。
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
    void uploadsImageAndOnlySignedUrlIsReadable() throws Exception {
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
        assertThat(url).startsWith("/api/files/image?")
                .contains("tenantId=1")
                .contains("signature=");

        // 直连对象存储不带应用签名必须被拒，避免泄露工单图片。
        RestClient client = RestClient.create();
        String directUrl = storage.getEndpoint() + "/" + storage.getBucket() + "/" + objectKey;
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> client.get().uri(directUrl).retrieve()
                        .toEntity(byte[].class))
                .isInstanceOf(HttpClientErrorException.Forbidden.class);

        // 应用签名地址不要求再带登录 token，但只能在有效期内读取该对象。
        mockMvc.perform(get(URI.create(url)))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("image/png"))
                .andExpect(content().bytes(PNG_BYTES));

        String tamperedQuery = URI.create(url).getRawQuery()
                .replaceFirst("signature=[0-9a-f]+", "signature=00");
        mockMvc.perform(get(URI.create("/api/files/image?" + tamperedQuery)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(10006));

        String expiredQuery = URI.create(url).getRawQuery().replaceFirst("expires=[0-9]+", "expires=1");
        mockMvc.perform(get(URI.create("/api/files/image?" + expiredQuery)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(10006));

        String foreignTenantQuery = URI.create(url).getRawQuery().replaceFirst("tenantId=1", "tenantId=2");
        mockMvc.perform(get(URI.create("/api/files/image?" + foreignTenantQuery)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(10006));

        // 对象没有关联到当前可见工单前，不能签发访问地址。
        mockMvc.perform(post("/api/files/image-url")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("reference", objectKey))))
                .andExpect(jsonPath("$.code").value(10006));

        // 关联到工单后，提交者可以换取短期地址；同租户的另一个学生仍不能读取。
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/student/tickets")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "idempotencyKey", "image-access-" + System.nanoTime(),
                                "repairCode", "482913", "categoryId", 1,
                                "description", "图片权限测试", "images", List.of(objectKey)))))
                .andExpect(jsonPath("$.code").value(0));
        mockMvc.perform(post("/api/files/image-url")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("reference", objectKey))))
                .andExpect(jsonPath("$.code").value(0));

        String otherStudent = givenToken("test-upload-other-student");
        mockMvc.perform(post("/api/files/image-url")
                        .header("Authorization", "Bearer " + otherStudent)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("reference", objectKey))))
                .andExpect(jsonPath("$.code").value(10006));
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
