package com.bluemalic.repair.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TextLimitsTest {

    @Test
    void nullAndShortTextAreUnchanged() {
        assertThat(TextLimits.truncate(null, 10)).isNull();
        assertThat(TextLimits.truncate("短文本", 10)).isEqualTo("短文本");
        assertThat(TextLimits.truncate("刚好十个字", 5)).isEqualTo("刚好十个字");
    }

    @Test
    void longTextIsTruncatedToColumnWidth() {
        String text = "长".repeat(300);

        assertThat(TextLimits.truncate(text, TextLimits.TICKET_LOG_REMARK)).hasSize(255);
        assertThat(TextLimits.truncate(text, TextLimits.NOTIFICATION_CONTENT)).hasSize(255);
        assertThat(TextLimits.truncate(text, TextLimits.NOTIFICATION_TITLE)).hasSize(64);
    }
}
