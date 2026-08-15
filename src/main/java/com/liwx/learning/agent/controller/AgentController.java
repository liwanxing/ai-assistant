package com.liwx.learning.agent.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.util.concurrent.RateLimiter;
import com.liwx.learning.agent.service.AiClientService;
import com.liwx.learning.agent.tool.ResearchTool;
import com.liwx.learning.agent.tool.RagTool;
import com.liwx.learning.agent.tool.TimeTool;
import com.liwx.learning.agent.tool.UserQueryTool;
import com.liwx.learning.agent.tool.WeatherTool;
import com.liwx.learning.common.FileValidator;
import com.liwx.learning.rag.entity.ChatSession;
import com.liwx.learning.rag.mapper.ChatSessionMapper;
import org.springframework.core.io.FileSystemResource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
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
 *     问"分析销售趋势" → 模型调 MCP 远程工具 analyzeBusiness（跨系统调对方经营分析服务）
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
    private AiClientService aiClientService;

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
    private ResearchTool researchTool;

    @Value("${rag.upload-dir:./uploads}")
    private String uploadDir;

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

        // 限流：每 10 秒最多 1 次
        if (!getRateLimiter(String.valueOf(userId)).tryAcquire()) {
            return Flux.just("请求过于频繁，请稍后再试", "[DONE]");
        }

        // 会话管理：新会话自动创建，老会话更新活跃时间
        ChatSession existingSession = chatSessionMapper.selectBySessionId(sessionId);
        if (existingSession == null) {
            String title = question.length() > 20 ? question.substring(0, 20) + "..." : question;
            chatSessionMapper.insertIfNotExists(sessionId, title);
        } else {
            chatSessionMapper.updateActiveTime(sessionId);
        }

        log.info("Agent 收到对话：userId={}, question={}, sessionId={}", userId, question, sessionId);

        // 调用 AiClientService：@CircuitBreaker 保护，连续失败自动熔断降级
        String response = aiClientService.chat(question, sessionId, userId);
        return toSseFlux(response);
    }

    /**
     * Agent 图片对话（多模态）：支持图片 + 文本，模型同时理解图片和文字
     * 用法：POST /agent/chatWithImage?question=这张图片是什么&sessionId=xxx（form-data, field=image）
     */
    @PostMapping(value = "/chatWithImage", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatWithImage(
            @RequestParam String question,
            @RequestParam String sessionId,
            @RequestParam("image") MultipartFile imageFile) {
        final Long userId = StpUtil.getLoginIdAsLong();

        // 限流
        if (!getRateLimiter(String.valueOf(userId)).tryAcquire()) {
            return Flux.just("请求过于频繁，请稍后再试", "[DONE]");
        }

        // 校验图片格式（png/jpg/jpeg/gif/webp，最大 10MB）
        String ext = FileValidator.validate(imageFile, "png,jpg,jpeg,gif,webp", 10);

        // 会话管理
        ChatSession existingSession = chatSessionMapper.selectBySessionId(sessionId);
        if (existingSession == null) {
            String title = question.length() > 20 ? question.substring(0, 20) + "..." : question;
            chatSessionMapper.insertIfNotExists(sessionId, title);
        } else {
            chatSessionMapper.updateActiveTime(sessionId);
        }

        log.info("Agent 收到图片对话：userId={}, question={}, sessionId={}, imageSize={}KB",
                userId, question, sessionId, imageFile.getSize() / 1024);

        try {
            // 1. 保存图片到磁盘
            String filename = UUID.randomUUID() + ext;
            Path imageDir = Path.of(uploadDir, "chat-images").toAbsolutePath();
            Files.createDirectories(imageDir);
            Path dest = imageDir.resolve(filename);
            imageFile.transferTo(dest.toFile());
            String imageUrl = "/uploads/chat-images/" + filename;

            // 2. 确定 MIME 类型
            String contentType = switch (ext) {
                case ".png" -> "image/png";
                case ".jpg", ".jpeg" -> "image/jpeg";
                case ".gif" -> "image/gif";
                case ".webp" -> "image/webp";
                default -> "image/png";
            };

            // 3. 调用 AiClientService：@CircuitBreaker 保护，连续失败自动熔断降级
            String response = aiClientService.chatWithImage(
                    question, sessionId, userId,
                    imageUrl, contentType,
                    new FileSystemResource(dest));
            return toSseFlux(response);
        } catch (Exception e) {
            log.error("图片对话处理失败", e);
            return Flux.just("图片处理失败，请重试", "[DONE]");
        }
    }

    /**
     * 把完整回答拆成多行，每行作为独立的 SSE 事件发出
     *
     * 原因：SSE 协议用 data: 前缀标记数据，换行是事件分隔符。
     * 如果一整段带换行的 markdown 作为一个 Flux 元素发出，只有第一行有 data: 前缀，
     * 前端解析时其余行全丢了。
     * 解决：按 \n 拆成多行，每行单独走 data: 事件，前端每行追加时补回 \n。
     */
    private Flux<String> toSseFlux(String response) {
        if (response == null || response.isBlank()) {
            return Flux.just("未生成回答", "[DONE]");
        }
        List<String> lines = new ArrayList<>();
        // split 第二个参数 -1：保留末尾空字符串，确保 markdown 段落间的空行不丢
        for (String line : response.split("\n", -1)) {
            lines.add(line);
        }
        lines.add("[DONE]");
        return Flux.fromIterable(lines);
    }
}
