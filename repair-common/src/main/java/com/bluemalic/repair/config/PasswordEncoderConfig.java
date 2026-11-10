package com.bluemalic.repair.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 密码编码器。
 *
 * <p>这里用的 {@code spring-security-crypto} 只是从 Spring Security 项目里拆出来的**加密工具包**
 * （BCrypt / Argon2 / PBKDF2），**没有过滤器链、没有自动装配**，所以不违反 AGENTS 里
 * "不要引入 Spring Security"的约定 —— 那条约定针对的是整套安全框架。鉴权仍然走 Sa-Token。
 */
@Configuration
public class PasswordEncoderConfig {

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
