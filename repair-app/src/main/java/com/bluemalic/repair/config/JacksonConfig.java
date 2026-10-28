package com.bluemalic.repair.config;

import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalDateTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.deser.LocalTimeDeserializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalTimeSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.format.DateTimeFormatter;

/**
 * 全局 JSON 序列化约定（见 docs/03-接口规范.md 第 2.2 节）。
 */
@Configuration
public class JacksonConfig {

    private static final String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";
    private static final String DATE_PATTERN = "yyyy-MM-dd";
    private static final String TIME_PATTERN = "HH:mm:ss";

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jacksonCustomizer() {
        DateTimeFormatter dateTime = DateTimeFormatter.ofPattern(DATE_TIME_PATTERN);
        DateTimeFormatter date = DateTimeFormatter.ofPattern(DATE_PATTERN);
        DateTimeFormatter time = DateTimeFormatter.ofPattern(TIME_PATTERN);

        return builder -> {
            // 雪花 ID 约 1.9×10^18，超出 JS 的 Number.MAX_SAFE_INTEGER（2^53-1）。
            // 不转成字符串，前端解析后末尾几位会被抹掉，表现为"详情查不到"这类诡异问题。
            builder.serializerByType(Long.class, ToStringSerializer.instance);
            builder.serializerByType(Long.TYPE, ToStringSerializer.instance);

            builder.serializerByType(java.time.LocalDateTime.class, new LocalDateTimeSerializer(dateTime));
            builder.serializerByType(java.time.LocalDate.class, new LocalDateSerializer(date));
            builder.serializerByType(java.time.LocalTime.class, new LocalTimeSerializer(time));

            builder.deserializerByType(java.time.LocalDateTime.class, new LocalDateTimeDeserializer(dateTime));
            builder.deserializerByType(java.time.LocalDate.class, new LocalDateDeserializer(date));
            builder.deserializerByType(java.time.LocalTime.class, new LocalTimeDeserializer(time));
        };
    }
}
