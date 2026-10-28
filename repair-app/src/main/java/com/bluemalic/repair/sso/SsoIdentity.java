package com.bluemalic.repair.sso;

/**
 * 统一认证返回的身份（阶段 7 · ADR-022）。
 *
 * @param subject   IdP 那边的唯一标识（OAuth2 的 sub / CAS 的用户名），**只用于日志与记录**
 * @param username  映射成平台登录名的值（学号 / 工号）——账号必须**已经存在**于这所学校，
 *                  平台不会因为 SSO 通过就自动建号（配额与角色不该被外部系统绕过）
 * @param realName  IdP 返回的姓名；没给就是 null（平台里已有姓名时不会覆盖）
 */
public record SsoIdentity(String subject, String username, String realName) {
}
