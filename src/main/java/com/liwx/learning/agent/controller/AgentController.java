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
import com.liwx.learning.common.FileValidator;
import com.liwx.learning.rag.advisor.UserMemoryAdvisor;
import com.liwx.learning.rag.entity.ChatSession;
import com.liwx.learning.rag.mapper.ChatSessionMapper;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.util.MimeType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.nio.file.Files;
import java.nio.file.Path;
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
     * 图片对话（多模态）：用户上传图片 + 问题，模型直接"看"图片回答
     *
     * 多模态原理：
     *   1. 图片转 base64 → 和文本一起发给通义 qwen-vl-plus 视觉模型
     *   2. 模型不需要先 OCR 转文字，直接理解图片内容（端到端多模态）
     *   3. 返回标准文本回答，前端 Markdown 渲染
     *
     * 存储方案：
     *   图片存本地 → URL 存在消息文本的 markdown 语法中 → ChatMemory 只存文本（MediaStrippingChatMemory 剥离 base64）
     *   历史记录加载时前端解析 markdown 图片语法显示缩略图
     */
    @PostMapping(value = "/chat-with-image", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatWithImage(
            @RequestParam String question,
            @RequestParam String sessionId,
            @RequestParam("image") MultipartFile imageFile) {
        final Long userId = StpUtil.getLoginIdAsLong();

        // 限流
        if (!getRateLimiter(String.valueOf(userId)).tryAcquire()) {
            return Flux.just("请求过于频繁，请稍后再试");
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

            // 2. 构建消息文本：markdown 图片语法（前端渲染缩略图）+ 用户问题
            String markdownText = "![图片](" + imageUrl + ")\n\n" + question;

            // 3. 确定 MIME 类型
            String contentType = switch (ext) {
                case ".png" -> "image/png";
                case ".jpg", ".jpeg" -> "image/jpeg";
                case ".gif" -> "image/gif";
                case ".webp" -> "image/webp";
                default -> "image/png";
            };

            // 4. 调用多模态模型（qwen-vl-plus）：图片转 base64 和文本一起发给模型
            //    .options() 覆盖默认的 qwen-plus 为 qwen-vl-plus（视觉理解模型）
            String systemPrompt = "你是一个智能助手，可以根据用户问题和图片内容进行分析和回答。" +
                    "如果用户上传了截图，请仔细识别图片内容并给出有用的回答。";

            String response = chatClient.prompt()
                    .system(systemPrompt)
                    .user(u -> u.text(markdownText).media(
                            MimeType.valueOf(contentType),
                            new FileSystemResource(dest)))
                    .options(OpenAiChatOptions.builder().model("qwen-vl-plus"))
                    .tools(ragTool, timeTool, weatherTool, userQueryTool, graphTool, researchTool)
                    .advisors(a -> {
                        a.param(ChatMemory.CONVERSATION_ID, sessionId);
                        a.param(UserMemoryAdvisor.USER_ID, userId);
                    })
                    .call()
                    .content();

            return Flux.just(response == null ? "未生成回答" : response, "[DONE]");
        } catch (Exception e) {
            log.error("图片对话调用失败：{}", e.getMessage());
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
