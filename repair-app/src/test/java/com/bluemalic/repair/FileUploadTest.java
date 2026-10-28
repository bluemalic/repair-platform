package com.bluemalic.repair;

import com.bluemalic.repair.entity.SysUser;
import com.bluemalic.repair.entity.SysUserRole;
import com.bluemalic.repair.mapper.SysUserMapper;
import com.bluemalic.repair.mapper.SysUserRoleMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 上传接口的**校验层**验证：不碰 MinIO，所以本地没起 MinIO 也能跑，CI 里也不依赖它。
 *
 * <p>真实上传另见 {@code FileUploadStorageTest}（那条需要 MinIO，因为对象存储是外部依赖、
 * 事务回滚不了它）。这种拆法也能回答"上传会不会被 A1（测试策略）挡住"——不会：
 * MockMvc 支持 multipart，而且照样跑在测试同线程、照样看得到未提交数据。
 *
 * <p>其中最要紧的一条是"改名成 jpg 的 HTML 必须被拒"：只看后缀或 Content-Type 的话，
 * 上传一个 .html 就能在图片 URL 上执行脚本（存储型 XSS）。
 */
@IntegrationTest
class FileUploadTest {

    private static final String PASSWORD = "Test@123456";
    private static final long ROLE_STUDENT = 1L;

    /** PNG / GIF 的真实文件头：只校验前几个字节，所以不必造完整可解码的图片。 */
    private static final byte[] PNG_HEAD = {
            (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 1, 2, 3};
    private static final byte[] GIF_HEAD = {'G', 'I', 'F', '8', '9', 'a', 0, 0, 0, 0, 0, 0, 0};

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

    @Test
    void uploadRequiresLogin() throws Exception {
        mockMvc.perform(multipart("/api/files/upload")
                        .file(new MockMultipartFile("file", "a.png", "image/png", PNG_HEAD)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(10002));
    }

    @Test
    void uploadRejectsEmptyFile() throws Exception {
        upload(givenToken("test-upload-empty"), new MockMultipartFile("file", "a.png", "image/png", new byte[0]))
                .andExpect(jsonPath("$.code").value(10001))
                .andExpect(jsonPath("$.message").value("请选择要上传的文件"));
    }

    @Test
    void uploadRejectsHtmlRenamedToJpg() throws Exception {
        byte[] html = "<html><script>alert(1)</script></html>".getBytes(StandardCharsets.UTF_8);

        // 文件名是 .jpg、声明的 Content-Type 也是 image/jpeg —— 但内容是 HTML，必须拒
        upload(givenToken("test-upload-xss"),
                new MockMultipartFile("file", "evil.jpg", "image/jpeg", html))
                .andExpect(jsonPath("$.code").value(50001))
                .andExpect(jsonPath("$.message").value("只允许 jpg / png / webp 图片"));
    }

    @Test
    void uploadRejectsRealImageOutsideWhitelist() throws Exception {
        // GIF 是真图片、无害，但不在白名单里：白名单就是白名单，不"顺便支持"
        upload(givenToken("test-upload-gif"),
                new MockMultipartFile("file", "a.gif", "image/gif", GIF_HEAD))
                .andExpect(jsonPath("$.code").value(50001));
    }

    @Test
    void uploadRejectsFileLargerThanBusinessLimit() throws Exception {
        // 业务上限 5MB；这里给 5MB + 1 字节，且内容确实是 PNG（先过大小的判定）
        byte[] tooBig = new byte[5 * 1024 * 1024 + 1];
        System.arraycopy(PNG_HEAD, 0, tooBig, 0, PNG_HEAD.length);

        upload(givenToken("test-upload-big"),
                new MockMultipartFile("file", "big.png", "image/png", tooBig))
                .andExpect(jsonPath("$.code").value(50002))
                .andExpect(jsonPath("$.message").value("图片不能超过 5 MB"));
    }

    private org.springframework.test.web.servlet.ResultActions upload(String token, MockMultipartFile file)
            throws Exception {
        return mockMvc.perform(multipart("/api/files/upload")
                        .file(file)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
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
