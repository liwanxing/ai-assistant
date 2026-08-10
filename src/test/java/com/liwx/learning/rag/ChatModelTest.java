package com.liwx.learning.rag;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Chat 模型测试：验证通义 DashScope 对话能力
 * <p>
 * 对应原 AiController 的功能，但用单元测试验证更合理：
 * - 可断言结果，不只是看打印
 * - 不需要启动 Postman/HTTP Client 手动调
 * - 跑完就结束，不占用 HTTP 端口
 * <p>
 * 前置条件：通义 DashScope API Key 有效（application.yml 中配置）
 *
 * @see ChatClient
 */
@SpringBootTest
class ChatModelTest {

    // Spring AI 读到 application.yml 里的 spring.ai.openai 配置后，自动创建：
    //   1. OpenAiChatModel（底层发 HTTP 请求到百炼的 chat 接口）
    //   2. ChatClient.Builder（内部引用了上面的 ChatModel）
    // 你注入 Builder → build() 拿到 ChatClient → 底层已绑定 ChatModel，不需要手动 new
    @Autowired
    private ChatClient.Builder chatClientBuilder;

    private ChatClient chatClient;

    @BeforeEach
    void setUp() {
        // build 一次复用，和 AiController 里的构造函数做的事一样
        this.chatClient = chatClientBuilder.build();
    }

    /**
     * 验证对话：发一个问题给通义，拿到非空回答
     * <p>
     * 调用链路：
     * prompt() → user(question) → call() → content()
     * 就是：开始构建对话 → 加用户消息 → 同步调模型 → 取文本
     */
    @Test
    void shouldReturnAnswerWhenAskQuestion() {
        String question = "用一句话介绍 Spring Boot";

        String answer = chatClient.prompt()
                .user(question)
                .call()
                .content();

        System.out.println("========== Chat 对话测试 ==========");
        System.out.println("问题：" + question);
        System.out.println("回答：" + answer);
        System.out.println("===================================");

        assertNotNull(answer, "回答不应为 null");
        assertFalse(answer.isBlank(), "回答不应为空字符串");
    }
}
