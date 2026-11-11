package com.bluemalic.repair.ai;

/**
 * 模型返回文本的公共清洗。
 *
 * <p><b>为什么抽出来</b>：模型很爱把结构化输出包在 markdown 围栏里（{@code ```json ... ```}），
 * 而"剥掉围栏"这件事在 M4 的问数链路里出现过、Agent 受理/分诊里出现过、维修建议里又要有——
 * 同一段逻辑抄第三遍之后，三处的口径迟早会漂（换个模型说"围栏带语言标记"时只修一处）。
 * 按项目自己的标准（{@code Paging} 就是这么抽出来的）：一模一样、出现三次以上就该收口。
 *
 * <p>纯函数、无状态，所有调用方共用同一套判断。
 *
 * <p><b>唯一没有换到这里的是 {@code SqlSafetyGateway}</b>：它的围栏处理嵌在一段更长的
 * {@code normalize} 里（后面还跟着「去掉结尾分号」「空串直接拒」这些**安全语义**的步骤），
 * 把那一段拆开换取「少两行重复」不划算——安全代码的可读性优先级高于 DRY。
 */
public final class ModelReplies {

    private ModelReplies() {
    }

    /**
     * 剥掉 markdown 代码围栏：` ``` ` 开头那一行整行去掉，结尾的 ` ``` ` 也去掉。
     * 没有围栏（或只有半截）时原样返回，只做 trim——**不猜、不补**：
     * 清洗的边界是"把模型最常加的那层包装去掉"，解析失败与否仍由调用方决定。
     */
    public static String stripFence(String raw) {
        String text = raw == null ? "" : raw.trim();
        if (text.startsWith("```")) {
            int firstLineEnd = text.indexOf('\n');
            if (firstLineEnd > 0) {
                text = text.substring(firstLineEnd + 1).trim();
            }
            if (text.endsWith("```")) {
                text = text.substring(0, text.length() - 3).trim();
            }
        }
        return text;
    }
}
