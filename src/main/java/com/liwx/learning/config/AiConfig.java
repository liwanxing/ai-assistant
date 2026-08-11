package com.liwx.learning.config;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AI 统一配置类
 * <p>
 * 为什么把 ChatClient 构建放在配置类而不是 Controller 构造函数里：
 * 1. 单一职责：配置类管"怎么构建"，Controller 只管"怎么用"
 * 2. 统一管理：以后加默认 system prompt、调整参数，只改这一个地方，所有 Controller 自动生效
 * 3. 符合 Spring 规范：Bean 的创建和注入分离，构造函数只做赋值
 */
@Configuration
public class AiConfig {

    /**
     * 构建 ChatClient Bean
     * Spring AI 自动配置会提供 ChatClient.Builder，这里 build 成单例复用
     */
    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }
}
