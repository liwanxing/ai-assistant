package com.liwx.aiassistant.agent.controller;

import cn.dev33.satoken.stp.StpUtil;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.util.concurrent.RateLimiter;
import com.liwx.aiassistant.agent.service.AiClientService;
import com.liwx.aiassistant.common.FileValidator;
import com.liwx.aiassistant.chat.entity.ChatSession;
import com.liwx.aiassistant.chat.mapper.ChatSessionMapper;
import org.springframework.core.io.FileSystemResource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
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

@Slf4j
@RestController
@RequestMapping("/agent")
public class AgentController {

    private final AiClientService aiClientService;

    private final ChatSessionMapper chatSessionMapper;

    @Value("${rag.upload-dir:./uploads}")
    private String uploadDir;

    private final Cache<String, RateLimiter> rateLimiters = CacheBuilder.newBuilder()
            .expireAfterAccess(1, TimeUnit.HOURS)
            .build();
    private RateLimiter getRateLimiter(String userId) {
        return rateLimiters.asMap().computeIfAbsent(userId, k -> RateLimiter.create(0.167));
    }

    public AgentController(AiClientService aiClientService, ChatSessionMapper chatSessionMapper) {
        this.aiClientService = aiClientService;
        this.chatSessionMapper = chatSessionMapper;
    }

    /** Agent 文本对话：模型自主决定调用哪个工具，SSE 流式返回 */
    @GetMapping(value = "/chat-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatStream(@RequestParam String question, @RequestParam String sessionId) {
        final Long userId = StpUtil.getLoginIdAsLong();
        if (!getRateLimiter(String.valueOf(userId)).tryAcquire()) {
            return Flux.just("请求过于频繁，请稍后再试", "[DONE]");
        }

        // 会话表只存元数据（标题、活跃时间），对话内容走 Spring AI 的记忆表——记忆分两层：壳和内容
        // 这里的标题没必要根据记录动态更新摘要标题，标题反而找不到，代码做减法才是高级；动态更新摘要标题是笔记的玩法。
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

    /** Agent 图片对话：支持图片+文本多模态，模型同时理解图片和文字 */
    @PostMapping(value = "/chat-with-image-stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> chatWithImageStream(
            @RequestParam String question,
            @RequestParam String sessionId,
            @RequestParam("image") MultipartFile imageFile) {
        final Long userId = StpUtil.getLoginIdAsLong();
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

    private Flux<String> toSseFlux(String response) {
        if (response == null || response.isBlank()) {
            return Flux.just("未生成回答", "[DONE]");
        }
        List<String> lines = new ArrayList<>();
        for (String line : response.split("\n", -1)) {
            lines.add(line);
        }
        lines.add("[DONE]");
        return Flux.fromIterable(lines);
    }
}
