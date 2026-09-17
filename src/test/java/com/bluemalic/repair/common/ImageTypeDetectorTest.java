package com.bluemalic.repair.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 魔数识别（纯函数，不需要 Spring）。
 *
 * <p>这是**安全相关**的一段逻辑，所以单独测：它挡的是"把 .html 改名成 .jpg 上传"这条存储型 XSS 路径，
 * 一旦被放松（比如改成只看后缀），接口测试未必会发现，但这条会红。
 */
class ImageTypeDetectorTest {

    private static final byte[] PNG_HEAD = {
            (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D};
    private static final byte[] JPEG_HEAD = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10};
    private static final byte[] WEBP_HEAD = {
            'R', 'I', 'F', 'F', (byte) 0x8A, 0, 0, 0, 'W', 'E', 'B', 'P'};

    @Test
    void detectsAllowedImageTypesByMagicBytes() {
        assertThat(ImageTypeDetector.detect(PNG_HEAD))
                .isEqualTo(new ImageTypeDetector.ImageType("png", "image/png"));
        assertThat(ImageTypeDetector.detect(JPEG_HEAD))
                .isEqualTo(new ImageTypeDetector.ImageType("jpg", "image/jpeg"));
        assertThat(ImageTypeDetector.detect(WEBP_HEAD))
                .isEqualTo(new ImageTypeDetector.ImageType("webp", "image/webp"));
    }

    @Test
    void rejectsHtmlEvenThoughItCouldBeRenamedToJpg() {
        // 这就是要防的那条路：后缀叫 .jpg、Content-Type 声明 image/jpeg，但内容是 HTML
        byte[] html = "<html><script>alert(1)</script></html>".getBytes();
        assertThat(ImageTypeDetector.detect(html)).isNull();
    }

    @Test
    void rejectsOtherRealFormatsOutsideWhitelist() {
        // GIF 是真图片、也不是可执行内容，但不在白名单里 —— 白名单就是白名单，不"顺便支持"
        byte[] gif = {'G', 'I', 'F', '8', '9', 'a', 0, 0};
        assertThat(ImageTypeDetector.detect(gif)).isNull();

        // RIFF 开头但不是 WEBP（比如 WAV 音频）：只看前四个字节能被绕过
        byte[] wav = {'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'A', 'V', 'E'};
        assertThat(ImageTypeDetector.detect(wav)).isNull();
    }

    @Test
    void toleratesNullOrTooShortInput() {
        assertThat(ImageTypeDetector.detect(null)).isNull();
        assertThat(ImageTypeDetector.detect(new byte[0])).isNull();
        assertThat(ImageTypeDetector.detect(new byte[]{(byte) 0xFF, (byte) 0xD8})).isNull();
    }
}
