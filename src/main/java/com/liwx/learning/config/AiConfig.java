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
 * <p>
 * 关于 ChatClient.Builder 参数从哪来：
 * Spring AI 的自动配置会读取 application.yml 里的 spring.ai.openai.* 配置
 * （model、base-url、api-key），自动构造好 ChatModel，再包装成 ChatClient.Builder 注入进来。
 * 所以切换模型（qwen-plus → deepseek → gpt-4o）只需要改 YAML，不用动代码。
 * <p>
 * 对比早期版本：Spring AI 1.0 之前需要在代码里手动 new ChatModel()，手动传 model 名、api-key，
 * 切换模型就得改 Java 代码，体验很差。现在全部声明式配置，YAML 一改就生效。
 */
@Configuration
public class AiConfig {

    /**
     * 构建 ChatClient Bean
     * <p>
     * ChatClient.Builder 由 Spring AI 自动配置注入，已包含 application.yml 中的模型配置。
     * 这里只需 build() 一下，不需要传 model、api-key 等参数。
     */
    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }
}
