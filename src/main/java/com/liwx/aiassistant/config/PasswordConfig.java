package com.liwx.aiassistant.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * 密码加密配置
 *
 * 为什么用 BCrypt：
 *   1. 自带盐值：每次加密结果不同（防彩虹表攻击）
 *   2. 可调计算成本：strength=10 表示 2^10 次哈希迭代，硬件升级后可调高
 *   3. 不可逆：哈希后无法还原出明文密码
 *
 * 只引入了 spring-security-crypto（加密模块），不会触发 Spring Security 的过滤器链
 */
@Configuration
public class PasswordConfig {

    /**
     * strength=10 表示 2^10=1024 次哈希迭代，值越大越慢，越难暴力破解
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        // BCrypt 单向哈希，无法反向还原密码；每次使用随机 Salt，使相同密码生成不同哈希值。
        // Salt 会随哈希结果一起存储，但无需保密，主要用于防止彩虹表等预计算攻击。
        return new BCryptPasswordEncoder(10);
    }
}
