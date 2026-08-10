package com.liwx.learning.rag.controller;

import com.liwx.learning.common.Result;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AI 测试接口（临时，验证 Spring AI + 通义能跑通后可删）
 *
 * 验证目的：确认 Spring AI 依赖能引入、通义 DashScope 能调通、ChatClient 能返回答案
 * 跑通后这个类可以删掉，正式 RAG 接口会放在 rag 包下
 *
 * 调用链路：
 *   浏览器 GET /ai/test?question=你好
 *   → ChatClient 把 question 包成 OpenAI 格式请求
 *   → 发到通义 DashScope（base-url 指向它）的 /chat/completions
 *   → 通义返回回答
 *   → ChatClient 取出文本内容，包成 Result 返回
 */
@RestController
@RequestMapping("/ai")
public class AiController {

    private final ChatClient chatClient;

    // 注入 ChatClient.Builder（Spring AI 自动配置提供的 Bean），build 一次复用
    // ChatClient 是面向用户的主 API，Builder 是它的构造器
    public AiController(ChatClient.Builder chatClientBuilder) {
        this.chatClient = chatClientBuilder.build();
    }

    /**
     * 最简测试：把 question 发给通义，返回通义的回答
     * 用法：GET /ai/test?question=用一句话介绍 Spring Boot
     */
    @GetMapping("/test")
    public Result<String> test(@RequestParam String question) {
        // prompt() 开始构建一次对话
        // user(question) 加一条 user 角色的消息（就是用户问的话）
        // call() 同步调用模型
        // content() 取回答的文本部分
        String answer = chatClient.prompt().user(question).call().content();
        return Result.success(answer);
    }
}
