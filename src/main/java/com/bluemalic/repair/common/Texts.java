package com.bluemalic.repair.common;

/** 文本小工具：只放“没有更好归属”的纯函数。 */
public final class Texts {

    private Texts() {
    }

    public static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
