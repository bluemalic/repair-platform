package com.bluemalic.repair.ai;

import java.util.Set;

/**
 * 安全网关的产出：**通过校验并已注入租户条件的 SQL**，以及它用到的表。
 *
 * <p>{@code tables} 只用于日志与排查（"这条问题最后查了哪几张表"）——它在拦截与执行之间
 * 没有别的作用，不要拿它当安全判断的依据（安全判断在 {@link SqlSafetyGateway} 里已经做完了）。
 */
public record SqlSafetyResult(String sql, Set<String> tables) {
}
