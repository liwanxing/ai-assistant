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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
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
@RestController
@RequestMapping("/agent")
public class AgentController {

    @Autowired
    private ChatClient chatClient;

    @Autowired
    private RagTool ragTool;

    @Autowired
    private TimeTool timeTool;

    @Autowired
    private ChatSessionMapper chatSessionMapper;

    // 限流：复用 RagController 的策略
    private final Map<String, RateLimiter> rateLimiters = new ConcurrentHashMap<>();

    private RateLimiter getRateLimiter(String userId) {
        return rateLimiters.computeIfAbsent(userId, k -> RateLimiter.create(0.167));
    }

    /**
     * Agent 对话（流式）：模型自主决定是否调用工具
     * 用法：GET /agent/chat?question=请假怎么请？&sessionId=xxx
     *
     * .tools(ragTool, timeTool) 把工具注册给模型，模型看到工具描述后：
     *   需要查知识库 → 调 ragTool.searchKnowledge()
     *   需要查时间   → 调 timeTool.getCurrentTime()
     *   都不需要     → 直接回答（不调任何工具）
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

        // 流式生成：注册工具，模型自主决策
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
}
