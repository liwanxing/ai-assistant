package com.liwx.learning.rag.controller;

import com.liwx.learning.common.FileValidator;
import com.liwx.learning.common.Result;
import com.liwx.learning.rag.entity.ChatSession;
import com.liwx.learning.rag.entity.RagDocument;
import com.liwx.learning.rag.enums.SplitStrategy;
import com.liwx.learning.rag.mapper.ChatSessionMapper;
import com.liwx.learning.rag.mapper.RagDocumentMapper;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import com.liwx.learning.rag.service.RagService;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 知识库管理接口：文档上传、文档列表、会话管理
 * 问答功能已迁移到 AgentController（/agent/chat），通过 Function Calling 让模型自主决定是否调用 RAG
 */
@RestController
@RequestMapping("/rag")
public class RagController {

    @Autowired
    private RagService ragService;
    @Autowired
    private ChatMemory chatMemory;
    @Autowired
    private ChatSessionMapper chatSessionMapper;
    @Autowired
    private RagDocumentMapper ragDocumentMapper;

    @Value("${rag.upload-dir}")
    private String uploadDir;

    /**
     * 文档上传（异步）：
     * 1. 保存文件到本地
     * 2. 插表(PROCESSING)
     * 3. 异步切分+向量化
     * 4. 立即返回
     */
    @PostMapping("/upload")
    public Result<Map<String, Object>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "splitStrategy", defaultValue = "TOKEN") SplitStrategy splitStrategy) throws Exception {
        // 0. 文件校验（空文件、格式、大小）
        String ext = FileValidator.validate(file, "pdf,txt,doc,docx,md", 50);
        String originalName = file.getOriginalFilename();

        // 1. 保存文件到本地
        String storedName = UUID.randomUUID() + ext;  // 拼上 UUID 生成新文件名，防止同名文件互相覆盖
        Path dir = Path.of(uploadDir).toAbsolutePath();  // 必须用绝对路径：transferTo 传相对路径时，Tomcat 会解析到自己的临时目录下
        Files.createDirectories(dir);  // 目录不存在时自动创建
        Path dest = dir.resolve(storedName);  // 拼接：目录路径 + 文件名
        file.transferTo(dest.toFile());  // 把上传的文件写到磁盘

        // 2. 插入文档记录，状态标记为 PROCESSING
        RagDocument doc = new RagDocument();
        doc.setFileName(originalName);
        doc.setFilePath(dest.toString());
        doc.setFileSize(file.getSize());
        doc.setFileType(ext.substring(1));  // 去掉点号：.pdf → pdf
        doc.setStatus("PROCESSING");
        ragDocumentMapper.insert(doc);

        // 3. 异步处理：Tika 读取 → 切分 → 向量化 → 存 Milvus（不阻塞当前请求）
        ragService.processDocument(doc.getId(), dest.toString(), splitStrategy);

        // 4. 立即返回，不等处理完
        return Result.success(Map.of("documentId", doc.getId(), "status", "PROCESSING"));
    }

    /**
     * 文档列表：查询已上传的文档及处理状态
     * 前端用这个接口轮询，展示 PROCESSING → SUCCESS 的状态变化
     */
    @GetMapping("/documents")
    public Result<List<RagDocument>> documents() {
        return Result.success(ragDocumentMapper.selectAll());
    }

    /**
     * 删除文档：同步删除 Milvus 向量 + 本地文件 + MySQL 记录（软删除）
     */
    @DeleteMapping("/documents/{id}")
    public Result<Void> deleteDocument(@PathVariable Long id) {
        ragService.deleteDocument(id);
        return Result.success();
    }

    /**
     * 会话列表：查询所有历史会话，按最后活跃时间倒序
     * 前端左侧栏展示用
     */
    @GetMapping("/sessions")
    public Result<List<ChatSession>> sessions() {
        return Result.success(chatSessionMapper.selectAll());
    }

    /**
     * 消息记录：查询某个会话的所有聊天消息
     * 前端点击历史会话时调用，把消息加载到聊天区
     * 续聊原理：前端拿这个 sessionId 继续调 /agent/chat，后端 ChatMemory 自动加载历史上下文
     */
    // 匹配消息文本中的 markdown 图片语法：![图片](/uploads/chat-images/xxx.png)
    private static final Pattern IMAGE_PATTERN = Pattern.compile("!\\[[^]]*]\\((/uploads/[^)]+)\\)");

    @GetMapping("/sessions/{sessionId}/messages")
    public Result<List<Map<String, String>>> messages(@PathVariable String sessionId) {
        // ChatMemory 按 sessionId 从 SPRING_AI_CHAT_MEMORY 表加载所有消息
        List<Message> history = chatMemory.get(sessionId);
        // 转成前端需要的格式：过滤掉 SYSTEM 消息（系统提示词不需要展示）
        List<Map<String, String>> result = new ArrayList<>();
        for (Message msg : history) {
            if (msg.getMessageType() == MessageType.SYSTEM) {
                continue;
            }
            // 用 LinkedHashMap 保证输出顺序：role → content → imageUrl
            Map<String, String> item = new LinkedHashMap<>();
            item.put("role", msg.getMessageType() == MessageType.USER ? "user" : "ai");

            String content = msg.getText();
            // 解析 markdown 图片语法：把 ![图片](url) 提取成单独的 imageUrl 字段，从 content 中去掉
            Matcher matcher = IMAGE_PATTERN.matcher(content);
            if (matcher.find()) {
                item.put("imageUrl", matcher.group(1));
                content = matcher.replaceAll("").trim();
            }
            item.put("content", content);
            result.add(item);
        }
        return Result.success(result);
    }

    /**
     * 删除会话：删 rag_chat_session 记录 + 清空 SPRING_AI_CHAT_MEMORY 里的消息
     */
    @DeleteMapping("/sessions/{sessionId}")
    public Result<Void> deleteSession(@PathVariable String sessionId) {
        chatSessionMapper.deleteBySessionId(sessionId);
        chatMemory.clear(sessionId);
        return Result.success();
    }
}
