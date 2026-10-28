package com.bluemalic.repair.common;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.SerializerProvider;

import java.io.IOException;

/**
 * 把 long 按数字输出，而不是字符串。
 *
 * <p>全局约定是 Long → 字符串（保雪花 ID 精度），但分页的计数字段是明确例外：
 * total / pages 不可能接近 2^53，而前端分页组件（Element Plus 的 el-pagination）
 * 要求它们是 Number。用法：在字段上标 {@code @JsonSerialize(using = NumericLongSerializer.class)}。
 *
 * <p>为什么默认值取"转字符串"这一侧：方向选错时代价不对称——忘记处理计数只是类型难看，
 * 忘记处理 ID 是前端静默丢精度。详见 docs/03-接口规范.md 第 2.2 节。
 */
public class NumericLongSerializer extends JsonSerializer<Long> {

    @Override
    public void serialize(Long value, JsonGenerator gen, SerializerProvider serializers) throws IOException {
        if (value == null) {
            gen.writeNull();
        } else {
            gen.writeNumber(value);
        }
    }
}
