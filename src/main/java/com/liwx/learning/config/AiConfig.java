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
     * 构建 ChatClient Bean（现代写法：YAML 配置 + 自动注入 Builder）
     * <p>
     * ChatClient.Builder 由 Spring AI 自动配置注入，已包含 application.yml 中的模型配置。
     * 这里只需 build() 一下，不需要传 model、api-key 等参数。
     * <p>
     * ───────────────────────────────────────────────────────
     * ChatClient 和 ChatModel 的关系：
     * <p>
     * ChatClient（面向开发者的高级 API，链式调用 .prompt().user().call()）
     *   └─ 内部持有 ChatModel（真正调模型的角色）
     *        ├─ OpenAiChatModel（调通义/OpenAI 时，底层走 HTTP 请求）
     *        ├─ OllamaChatModel（调本地 Ollama 时）
     *        ├─ ZhiPuAiChatModel（调智谱时）
     *        └─ ... 每个平台一个实现类
     * <p>
     * ChatModel 是接口，定义了 call() 方法。不同平台有不同的实现类，
     * 就像 JDBC 的 Connection 接口，MySQL 和 Oracle 各有自己的实现。
     * <p>
     * ───────────────────────────────────────────────────────
     * 对比早期写法（手动构建，切换模型要改代码）：
     *
     * // 1. 手动创建 ChatModel，手动传所有参数
     * OpenAiApi openAiApi = new OpenAiApi(
     *     "https://dashscope.aliyuncs.com/compatible-mode/v1",  // base-url
     *     "sk-xxx",                                            // api-key
     *     "qwen-plus"                                          // model 名
     * );
     * OpenAiChatModel chatModel = new OpenAiChatModel(openAiApi);
     *
     * // 2. 手动创建 ChatClient
     * ChatClient chatClient = ChatClient.builder(chatModel).build();
     *
     * // 3. 换 DeepSeek？三行代码全得改：url、key、model 名
     * //    换 Ollama？整个类都得换掉，OpenAiApi → OllamaApi
     *
     * ───────────────────────────────────────────────────────
     * 现在写法（声明式配置，切换模型只改 YAML）：
     *
     * // YAML 配置：
     * // spring.ai.openai.base-url=...
     * // spring.ai.openai.api-key=...
     * // spring.ai.openai.chat.model=qwen-plus  ← 改这一行就能切换
     *
     * // 代码：Builder 自动注入，model 已经包在里面了
     * ChatClient chatClient(ChatClient.Builder builder) {
     *     return builder.build();  // 就这一行
     * }
     *
     * ───────────────────────────────────────────────────────
     */
    @Bean
    public ChatClient chatClient(ChatClient.Builder builder) {
        return builder.build();
    }
}
