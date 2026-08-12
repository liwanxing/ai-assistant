package com.liwx.learning.agent.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.google.common.util.concurrent.RateLimiter;
import com.liwx.learning.agent.tool.RagTool;
import com.liwx.learning.agent.tool.TimeTool;
import com.liwx.learning.rag.advisor.UserMemoryAdvisor;
import com.liwx.learning.rag.entity.ChatSession;
import com.liwx.learning.rag.mapper.ChatSessionMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 接口（Function Calling）
 *
 * 和 RagController 的区别：
 *   RagController（/rag/ask）：每个问题固定走向量检索，不管问什么都搜知识库
 *   AgentController（/agent/chat）：注册了多个工具，模型自己决定调哪个
 *     问"请假怎么请" → 模型调 RagTool（查知识库）
 *     问"现在几点"   → 模型调 TimeTool（查时间）
 *     问"你好"      → 不调任何工具，直接回答
 *
 * 面试一句话：用 Spring AI Function Calling 实现 Agent，
 *   RAG 作为工具注册进去，模型根据用户问题自主决定是否调用
 */
@Slf4j
@RestController
@RequestMapping("/agent")
public class AgentController {

    @Autowired
    private ChatClient chatClient;

    @Autowired
    private ChatModel chatModel;

    @Autowired
    private ChatSessionMapper chatSessionMapper;

    @Autowired
    private RagTool ragTool;

    @Autowired
    private TimeTool timeTool;

    // 限流：复用 RagController 的策略
    private final Map<String, RateLimiter> rateLimiters = new ConcurrentHashMap<>();

    private RateLimiter getRateLimiter(String userId) {
        return rateLimiters.computeIfAbsent(userId, k -> RateLimiter.create(0.167));
    }

    /**
     * Agent 对话（流式）：模型自主决定是否调用工具
     * 用法：GET /agent/chat?question=请假怎么请？&sessionId=xxx
     *
     * 工具在每次请求时通过 .tools() 注册，这是 Spring AI 官方推荐的做法
     */
    @GetMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chat(@RequestParam String question, @RequestParam String sessionId) {
        final Long userId = StpUtil.getLoginIdAsLong();

        // 限流
        if (!getRateLimiter(String.valueOf(userId)).tryAcquire()) {
            return Flux.just("请求过于频繁，请稍后再试");
        }

        // 会话管理：复用 RagController 的会话表
        ChatSession existingSession = chatSessionMapper.selectBySessionId(sessionId);
        if (existingSession == null) {
            String title = question.length() > 20 ? question.substring(0, 20) + "..." : question;
            chatSessionMapper.insertIfNotExists(sessionId, title);
        } else {
            chatSessionMapper.updateActiveTime(sessionId);
        }

        log.info("Agent 收到请求：userId={}, question={}, sessionId={}", userId, question, sessionId);

        // 流式生成：在请求级别注册工具
        return chatClient.prompt()
                .system("你是一个智能助手，可以根据用户问题自主选择是否调用工具。" +
                        "如果用户问的是知识库相关内容，调用搜索工具查找资料后回答，并在回答中标注参考资料编号。" +
                        "如果找不到相关资料，明确告知用户。")
                .user(question)
                .tools(ragTool, timeTool)
                .advisors(a -> {
                    a.param(ChatMemory.CONVERSATION_ID, sessionId);
                    a.param(UserMemoryAdvisor.USER_ID, userId);
                })
                .stream()
                .content()
                .concatWithValues("[DONE]");
    }

    /**
     * 非流式测试端点：验证 Function Calling 是否生效
     * 不走会话管理，不走记忆，最简链路排查工具调用
     */
    @PostMapping("/test-tool")
    public String testTool(@RequestBody Map<String, String> body) {
        String question = body.getOrDefault("question", "现在几点");
        log.info("测试 Function Calling（非流式）：question={}", question);

        String result = chatClient.prompt()
                .user(question)
                .tools(timeTool)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, "test-tool-session"))
                .call()
                .content();

        log.info("Function Calling 测试结果：{}", result);
        return result;
    }

    /**
     * 流式测试端点（直连 ChatModel）：绕过 ChatClient 和 Advisor 链
     * 用于定位问题：tools 在流式模式下丢失，到底是在 Advisor 链层还是在 ChatModel/API 层
     */
    @PostMapping("/test-tool-stream")
    public String testToolStream(@RequestBody Map<String, String> body) {
        String question = body.getOrDefault("question", "现在几点");
        log.info("=== 流式直连测试开始 ===");
        log.info("question={}", question);

        // 1. 构建 ToolCallback
        ToolCallback[] callbacks = ToolCallbacks.from(timeTool);
        log.info("ToolCallbacks 数量：{}", callbacks.length);
        for (ToolCallback tc : callbacks) {
            log.info("  Tool: name={}, description={}",
                    tc.getToolDefinition().name(), tc.getToolDefinition().description());
        }

        // 2. 构建 OpenAiChatOptions（带 tools）
        OpenAiChatOptions options = OpenAiChatOptions.builder()
                .toolCallbacks(callbacks)
                .build();
        log.info("OpenAiChatOptions.getToolCallbacks() = {}", options.getToolCallbacks());

        // 3. 构建 Prompt
        Prompt prompt = new Prompt(new UserMessage(question), options);
        ChatOptions promptOptions = prompt.getOptions();
        log.info("Prompt options class: {}", promptOptions.getClass().getName());
        if (promptOptions instanceof ToolCallingChatOptions tcOptions) {
            log.info("Prompt options toolCallbacks: {}", tcOptions.getToolCallbacks());
        }

        // 4. 直连 ChatModel.stream()，不走任何 Advisor
        StringBuilder result = new StringBuilder();
        StringBuilder finishReasons = new StringBuilder();

        chatModel.stream(prompt)
                .doOnNext(response -> {
                    Generation gen = response.getResult();
                    if (gen != null) {
                        String fr = gen.getMetadata().getFinishReason();
                        if (fr != null && !"null".equals(fr)) {
                            log.info("Chunk finishReason: {}", fr);
                            finishReasons.append(fr).append(",");
                        }
                        String text = gen.getOutput().getText();
                        if (text != null && !text.isEmpty()) {
                            result.append(text);
                        }
                    }
                })
                .doOnError(e -> log.error("流式请求异常", e))
                .doOnComplete(() -> log.info("流式请求完成"))
                .blockLast();

        log.info("所有 finishReason: {}", finishReasons.toString());
        log.info("流式直连测试结果：{}", result.toString());
        log.info("=== 流式直连测试结束 ===");

        return result.toString() + " | finishReasons: " + finishReasons.toString();
    }

}
