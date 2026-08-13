package com.liwx.learning.agent.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.util.concurrent.RateLimiter;
import com.liwx.learning.agent.tool.GraphTool;
import com.liwx.learning.agent.tool.ResearchTool;
import com.liwx.learning.agent.tool.RagTool;
import com.liwx.learning.agent.tool.TimeTool;
import com.liwx.learning.agent.tool.UserQueryTool;
import com.liwx.learning.agent.tool.WeatherTool;
import com.liwx.learning.rag.advisor.UserMemoryAdvisor;
import com.liwx.learning.rag.entity.ChatSession;
import com.liwx.learning.rag.mapper.ChatSessionMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Autowired;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

import java.util.concurrent.TimeUnit;

/**
 * Agent 接口（Function Calling）
 *
 * 和 RagController 的区别：
 *   RagController（/rag/ask）：每个问题固定走向量检索，不管问什么都搜知识库
 *   AgentController（/agent/chat）：注册了多个工具，模型自己决定调哪个
 *     问"请假怎么请" → 模型调 RagTool（查知识库）
 *     问"现在几点"   → 模型调 TimeTool（查时间）
 *     问"北京天气"   → 模型调 WeatherTool（查天气）
 *     问"有多少用户" → 模型调 UserQueryTool（查数据库）
 *     问"分析销售趋势" → 模型调 GraphTool（调Graph工作流做多步分析）
 *     问"调研Java AI框架" → 模型调 ResearchTool（调Python Agent做深度调研）
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
    private ChatSessionMapper chatSessionMapper;

    @Autowired
    private RagTool ragTool;

    @Autowired
    private TimeTool timeTool;

    @Autowired
    private WeatherTool weatherTool;

    @Autowired
    private UserQueryTool userQueryTool;

    @Autowired
    private GraphTool graphTool;

    @Autowired
    private ResearchTool researchTool;

    // 限流：Guava Cache 自动清理不活跃用户的 RateLimiter，避免内存泄漏
    private final Cache<String, RateLimiter> rateLimiters = CacheBuilder.newBuilder()
            .expireAfterAccess(1, TimeUnit.HOURS)
            .build();

    private RateLimiter getRateLimiter(String userId) {
        return rateLimiters.asMap().computeIfAbsent(userId, k -> RateLimiter.create(0.167));
    }

    /**
     * Agent 对话（流式）：模型自主决定是否调用工具
     * 用法：GET /agent/chat?question=请假怎么请？&sessionId=xxx
     */
    @GetMapping(value = "/chat", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chat(@RequestParam String question, @RequestParam String sessionId) {
        final Long userId = StpUtil.getLoginIdAsLong();

        // 限流
        if (!getRateLimiter(String.valueOf(userId)).tryAcquire()) {
            return Flux.just("请求过于频繁，请稍后再试");
        }

        // 会话管理
        ChatSession existingSession = chatSessionMapper.selectBySessionId(sessionId);
        if (existingSession == null) {
            String title = question.length() > 20 ? question.substring(0, 20) + "..." : question;
            chatSessionMapper.insertIfNotExists(sessionId, title);
        } else {
            chatSessionMapper.updateActiveTime(sessionId);
        }

        log.info("Agent 收到请求：userId={}, question={}, sessionId={}", userId, question, sessionId);

        String systemPrompt = "你是一个智能助手，可以根据用户问题自主选择是否调用工具。" +
                "如果用户问的是知识库相关内容，调用搜索工具查找资料后回答，并在回答中标注参考资料编号。" +
                "如果找不到相关资料，明确告知用户。" +
                "如果用户问用户信息、系统有多少人等，调用用户查询工具。" +
                "如果用户需要深度数据分析、经营报告、销售趋势等复杂分析，调用经营分析工具。" +
                "如果用户需要深入调研某个主题、对比技术方案、行业趋势分析等，调用深度调研工具。";

        // 使用非流式调用：DashScope 兼容模式流式 + 工具调用有已知 bug（后续 chunk 的 id 返回空字符串，
        // 导致 Spring AI 的 ChunkMerger 崩溃）。流式降级又会导致 ChatMemory 重复保存用户消息。
        // 综合考虑，用非流式 .call() 一次性返回，前端 SSE 仍然正常工作。
        try {
            String response = buildPrompt(systemPrompt, question, sessionId, userId)
                    .call()
                    .content();
            return Flux.just(response == null ? "未生成回答" : response, "[DONE]");
        } catch (Exception e) {
            log.error("Agent 调用失败：{}", e.getMessage());
            return Flux.just("请求处理失败，请重试", "[DONE]");
        }
    }

    /**
     * 构建 ChatClient 请求（复用给流式和非流式）
     */
    private ChatClient.ChatClientRequestSpec buildPrompt(String systemPrompt, String question,
                                                          String sessionId, Long userId) {
        return chatClient.prompt()
                .system(systemPrompt)
                .user(question)
                .tools(ragTool, timeTool, weatherTool, userQueryTool, graphTool, researchTool)
                .advisors(a -> {
                    a.param(ChatMemory.CONVERSATION_ID, sessionId);
                    a.param(UserMemoryAdvisor.USER_ID, userId);
                });
    }

}
