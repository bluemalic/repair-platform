package com.bluemalic.repair.common;

/**
 * 按**文件头魔数**判断图片真实类型。
 *
 * <p><b>为什么不看后缀、也不看客户端传的 Content-Type</b>：这两样都是客户端说了算，随手可改。
 * 把 `evil.html` 改名成 `evil.jpg` 上传，只看后缀就会放行；而这个对象一旦被浏览器当页面打开，
 * 里面的脚本就在你的域名下执行了（存储型 XSS）。**文件头改不了**——除非真把它改成合法图片，
 * 那它就不是 HTML 了。
 *
 * <p>只读开头十几个字节即可判断，不需要把整个文件读进来。
 */
public final class ImageTypeDetector {

    /** 需要读的最少字节数：WebP 要看到第 12 字节（RIFF....WEBP）。 */
    public static final int HEAD_BYTES = 12;

    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};
    private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final byte[] RIFF_MAGIC = {'R', 'I', 'F', 'F'};
    private static final byte[] WEBP_MAGIC = {'W', 'E', 'B', 'P'};

    private ImageTypeDetector() {
    }

    /**
     * @param head 文件开头的字节（建议 {@link #HEAD_BYTES} 个，不足也能判断出的类型会照常返回）
     * @return 识别出的类型；不是允许的图片格式返回 {@code null}（由调用方转成 50001）
     */
    public static ImageType detect(byte[] head) {
        if (head == null || head.length < JPEG_MAGIC.length) {
            return null;
        }
        if (startsWith(head, PNG_MAGIC)) {
            return new ImageType("png", "image/png");
        }
        if (startsWith(head, JPEG_MAGIC)) {
            // 统一存成 .jpg：jpeg 只是同一个格式的另一个后缀，两种都收，存一种即可
            return new ImageType("jpg", "image/jpeg");
        }
        if (startsWith(head, RIFF_MAGIC) && hasAt(head, 8, WEBP_MAGIC)) {
            return new ImageType("webp", "image/webp");
        }
        return null;
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        return hasAt(data, 0, prefix);
    }

    private static boolean hasAt(byte[] data, int offset, byte[] expected) {
        if (data.length < offset + expected.length) {
            return false;
        }
        for (int i = 0; i < expected.length; i++) {
            if (data[offset + i] != expected[i]) {
                return false;
            }
        }
        return true;
    }

    /** 识别结果：存储用的扩展名 + 服务端写死的 Content-Type（都不采信客户端）。 */
    public record ImageType(String extension, String contentType) {
    }
}
