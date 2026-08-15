package com.liwx.learning.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * 千问 DashScope「流式工具调用」bug 验证测试（一次性诊断 + 长期回归）
 *
 * 【背景】主链路 AiClientService 为什么用同步 .call() 而不是流式 .stream()：
 *   DashScope OpenAI 兼容模式在流式工具调用中，带参数工具的后续 chunk
 *   tool_call.id 字段返回空字符串 ""（标准 OpenAI 应该是字段缺失），
 *   导致 Spring AI 的 mergeDeltas 误判为"新工具调用"，最终抛 NoSuchElementException。
 *
 * 【测试原理】复现当初的炸法：流式 + 带参数的工具
 *   - 当初的现象：无参数工具能成功，带参数工具必炸（参数被拆成多个 chunk 传输才会触发）
 *   - 所以本测试的工具必须带参数，问题要逼模型必须调工具
 *
 * 【判定标准】
 *   - 测试通过（工具被调 + 流式拿到含工具数据的回答）→ bug 已修复，主链路可切 .stream()
 *   - 测试失败（流上抛 NoSuchElementException 等）→ bug 仍在，继续用 .call() + toSseFlux 假流
 *
 * 【隔离性】注入原始 ChatClient.Builder（不带 ChatMemory/摘要/长期记忆等默认 Advisor），
 * 不写数据库、不碰生产代码；工具是测试类内部的静态内部类，用完即弃。
 * 将来主链路切成流式后，本测试自动升级为回归测试（保证流式工具调用不再坏）。
 *
 * 前置条件：DashScope API Key 有效（application-local.yml / 环境变量）
 */
@SpringBootTest
class StreamToolCallVerifyTest {

    @Autowired
    private ChatClient.Builder chatClientBuilder;

    private ChatClient chatClient;

    @BeforeEach
    void setUp() {
        // 原始 Builder：不带 AiConfig 里 chatClient Bean 的那些默认 Advisor，最干净的环境
        this.chatClient = chatClientBuilder.build();
    }

    /**
     * 测试专用工具：必须带参数（bug 只在带参数工具上触发，参数被拆成多个 chunk 才会撞上 id 为空的场景）
     * 返回假天气数据：如果最终回答里出现"晴"或"25"，证明工具结果被正确回填给了模型
     */
    static class CityWeatherTool {

        static final AtomicBoolean INVOKED = new AtomicBoolean(false);

        @Tool(description = "查询指定城市的实时天气。当用户询问任何城市的天气时，必须调用此工具获取数据。")
        public String getWeather(@ToolParam(description = "城市名称，如：北京、上海") String city) {
            INVOKED.set(true);
            System.out.println(">>> 工具被调用，参数 city=" + city);
            return city + "：晴，气温25度，东南风2级";
        }
    }

    @Test
    void shouldSurviveStreamingToolCall() {
        CityWeatherTool.INVOKED.set(false);
        StringBuilder answer = new StringBuilder();
        int[] chunkCount = new int[1];

        try {
            // 复现当初的炸法：.stream() + 带参数工具
            // 走 chatResponse() 而不是 content()：保证 chunk 解析层（mergeDeltas）的异常能原样暴露
            chatClient.prompt()
                    .user("北京今天天气怎么样？请调用工具查询")
                    .tools(new CityWeatherTool())
                    .stream()
                    .chatResponse()
                    .doOnNext(response -> {
                        chunkCount[0]++;
                        if (response.getResult() != null && response.getResult().getOutput() != null
                                && response.getResult().getOutput().getText() != null) {
                            answer.append(response.getResult().getOutput().getText());
                        }
                    })
                    .blockLast(Duration.ofMinutes(3));
        } catch (Exception e) {
            // 挖到异常链最底层：当初的 bug 根因就是 mergeDeltas 里的 NoSuchElementException
            Throwable root = e;
            while (root.getCause() != null) {
                root = root.getCause();
            }
            System.out.println("========== 流式调用异常，根因：" + root + " ==========");
            e.printStackTrace();
            fail("千问流式工具调用仍失败 → bug 未修复，主链路继续用 .call() 同步调用。根因：" + root);
        }

        System.out.println("========== 流式工具调用完成，未抛异常 ==========");
        System.out.println("chunk 数量：" + chunkCount[0]);
        System.out.println("工具是否被调用：" + CityWeatherTool.INVOKED.get());
        System.out.println("最终回答：" + answer);

        // 工具必须被调用，否则测试无效（模型没走工具调用路径，等于没验证到 bug 场景）
        assertTrue(CityWeatherTool.INVOKED.get(), "模型没有调用工具，本次测试未覆盖目标场景，请换个更强制的问题重试");
        // 流式回答非空
        assertFalse(answer.toString().isBlank(), "流式回答不应为空");
        // 回答包含工具返回的假数据 → 工具结果被正确回填，模型基于结果生成了答案
        assertTrue(answer.toString().contains("晴") || answer.toString().contains("25"),
                "回答应包含工具返回的天气数据（晴/25），证明工具结果被正确回填给模型");

        System.out.println(">>> 结论：DashScope 流式工具调用 bug 已修复，主链路可以把 .call() 切成 .stream() 了");
    }
}
