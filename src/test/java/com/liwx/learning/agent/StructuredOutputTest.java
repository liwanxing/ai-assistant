package com.liwx.learning.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Structured Output（结构化输出）测试
 *
 * 和普通对话的区别：
 *   普通对话：.call().content() → 返回 String（自由文本，格式不可控）
 *   结构化输出：.call().entity(Class) → 返回 Java 对象（字段固定，类型安全）
 *
 * 原理：.entity(Class) 做了两件事：
 *   1. 请求时：把 Java 类转成 JSON Schema 塞进 prompt，告诉模型"按这个格式返回"
 *   2. 响应时：把模型返回的 JSON 自动反序列化成 Java 对象
 *
 * 应用场景：信息提取、情感分析、分类打标、结构化报告等需要稳定 JSON 格式的场景
 *
 * 前置条件：通义 DashScope API Key 有效（application.yml 中配置）
 */
@SpringBootTest
@Tag("integration")  // 集成测试：需真实 API Key + 完整 Spring 上下文，mvn test 默认排除，手动跑：mvn test -Dgroups=integration
class StructuredOutputTest {

    @Autowired
    private ChatClient.Builder chatClientBuilder;

    private ChatClient chatClient;

    @BeforeEach
    void setUp() {
        this.chatClient = chatClientBuilder.build();
    }

    /**
     * 定义模型返回的结构：Java record 就是 JSON Schema
     * 模型会按这个结构返回 JSON，Spring AI 自动反序列化成这个对象
     */
    record ExtractionResult(
            String summary,            // 一句话摘要
            List<String> keywords,     // 关键词列表
            String sentiment,          // 情感倾向：正面/负面/中性
            String category,           // 内容分类
            Integer priority           // 优先级 1-5（5最高）
    ) {}

    /**
     * 测试：投诉文本 → 结构化提取
     *
     * 输入：一段客户投诉文本
     * 输出：ExtractionResult 对象（摘要、关键词、情感、分类、优先级）
     */
    @Test
    void shouldExtractStructuredDataFromComplaint() {
        String text = "你好，我上周买的手机屏幕有坏点，需要紧急退货，请尽快处理！";

        ExtractionResult result = chatClient.prompt()
                .system("你是一个文本分析助手。分析用户输入的文本，提取关键信息。" +
                        "summary 用一句话概括；keywords 提取3-5个关键词；" +
                        "sentiment 判断正面/负面/中性；category 给出内容分类；priority 根据紧急程度打1-5分。")
                .user(text)
                .call()
                .entity(ExtractionResult.class);  // ← 关键：直接返回 Java 对象

        System.out.println("========== Structured Output 测试 ==========");
        System.out.println("输入文本：" + text);
        System.out.println("摘要：" + result.summary());
        System.out.println("关键词：" + result.keywords());
        System.out.println("情感倾向：" + result.sentiment());
        System.out.println("分类：" + result.category());
        System.out.println("优先级：" + result.priority());
        System.out.println("============================================");

        assertNotNull(result, "结果不应为 null");
        assertNotNull(result.summary(), "摘要不应为 null");
    }

    /**
     * 测试：正面情感文本 → 结构化提取
     */
    @Test
    void shouldExtractStructuredDataFromPositiveFeedback() {
        String text = "你们的产品真的太好用了，界面简洁，响应速度快，强烈推荐给同事！";

        ExtractionResult result = chatClient.prompt()
                .system("你是一个文本分析助手。分析用户输入的文本，提取关键信息。" +
                        "summary 用一句话概括；keywords 提取3-5个关键词；" +
                        "sentiment 判断正面/负面/中性；category 给出内容分类；priority 根据紧急程度打1-5分。")
                .user(text)
                .call()
                .entity(ExtractionResult.class);

        System.out.println("========== Structured Output 测试 ==========");
        System.out.println("输入文本：" + text);
        System.out.println("情感倾向：" + result.sentiment());
        System.out.println("优先级：" + result.priority());
        System.out.println("============================================");

        assertNotNull(result, "结果不应为 null");
    }
}
