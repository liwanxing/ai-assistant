package com.liwx.learning.rag.controller;

import com.liwx.learning.common.Result;
import com.liwx.learning.rag.entity.ChatSession;
import com.liwx.learning.rag.entity.RagDocument;
import com.liwx.learning.rag.enums.SplitStrategy;
import com.liwx.learning.rag.mapper.ChatSessionMapper;
import com.liwx.learning.rag.mapper.RagDocumentMapper;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import com.liwx.learning.rag.service.RagService;
import com.liwx.learning.rag.service.RerankService;
import com.liwx.learning.rag.advisor.UserMemoryAdvisor;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * RAG 问答接口（检索增强生成）
 * 链路：用户提问 → 向量检索知识库 → 大模型基于检索结果生成回答
 */
@RestController
@RequestMapping("/rag")
public class RagController {

    @Autowired
    private ChatClient chatClient;
    @Autowired
    private VectorStore vectorStore;
    @Autowired
    private RagService ragService;
    @Autowired
    private RerankService rerankService;
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
        // 1. 保存文件到本地
        String originalName = file.getOriginalFilename();
        String ext = originalName.substring(originalName.lastIndexOf("."));  // 取扩展名：.pdf
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
     * RAG 问答（流式 + 多轮对话）：根据知识库内容回答用户问题，支持上下文追问
     * 用法：GET /rag/ask?question=请假怎么请？&sessionId=xxx
     * sessionId：前端生成的会话标识，同一个 sessionId 下的问题会共享对话历史
     * 返回格式：text/event-stream（SSE），每条消息格式为 data:文字片段\n\n
     */
    @GetMapping(value = "/ask", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> ask(@RequestParam String question, @RequestParam String sessionId,
                            @RequestHeader(value = "satoken", required = false) String token) {
        // 0. 解析用户ID（/rag/ask 排除了 Sa-Token 拦截，手动从 token 解析）
        // 用临时变量解析，再赋值给 final 变量（lambda 表达式要求变量 effectively final）
        Long parsedUserId = null;
        if (token != null) {
            try {
                Object loginId = StpUtil.getLoginIdByToken(token);
                if (loginId != null) {
                    parsedUserId = Long.parseLong(loginId.toString());
                }
            } catch (Exception ignored) { }
        }
        final Long userId = parsedUserId;

        // 1. 会话管理：首次提问自动创建会话记录（标题取用户问题前 20 字），已有会话则刷新活跃时间
        ChatSession existingSession = chatSessionMapper.selectBySessionId(sessionId);
        if (existingSession == null) {
            String title = question.length() > 20 ? question.substring(0, 20) + "..." : question;
            chatSessionMapper.insertIfNotExists(sessionId, title);
        } else {
            chatSessionMapper.updateActiveTime(sessionId);
        }

        // 2. 向量检索：先从 Milvus 多召回一些候选（topK=10），给 Rerank 留筛选空间
        List<Document> documents = vectorStore.similaritySearch(SearchRequest.builder()
                .query(question)
                .topK(10)
                .build());

        // 3. Rerank 重排序：对 10 条候选用专门的排序模型重新打分，取最相关的 3 条
        if (!documents.isEmpty()) {
            documents = rerankService.rerank(question, documents, 3);
        }

        // 4. 把搜到的文档内容带编号拼接，方便大模型引用时标注来源
        StringBuilder contextBuilder = new StringBuilder();
        for (int i = 0; i < documents.size(); i++) {
            contextBuilder.append("【参考资料").append(i + 1).append("】\n")
                    .append(documents.get(i).getText())
                    .append("\n\n");
        }

        // 5. 流式生成 + 多轮记忆：advisors 传入 sessionId + userId
        // 记忆编排全部由 Advisor 链处理，Controller 只管传参：
        //   UserMemoryAdvisor(100)  → 注入长期记忆 + 异步提取
        //   MemoryAdvisor(默认)     → 加载对话历史
        //   SummaryAdvisor(500)     → 压缩溢出消息
        // 参考资料放 system prompt，user 只传纯问题
        return chatClient.prompt()
                .system("你是一个知识库问答助手。请根据以下参考资料回答用户的问题。" +
                        "回答时请在引用的内容后面标注来源，格式如[1]、[2]，对应参考资料的编号。" +
                        "如果参考资料中没有相关信息，请明确告知'根据现有资料无法回答此问题'，不要编造。" +
                        "\n\n参考资料：\n" + contextBuilder)
                .user(question)
                .advisors(a -> {
                    a.param(ChatMemory.CONVERSATION_ID, sessionId);
                    if (userId != null) {
                        a.param(UserMemoryAdvisor.USER_ID, userId);
                    }
                })
                .stream()
                .content()
                .concatWithValues("[DONE]");
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
     * 续聊原理：前端拿这个 sessionId 继续调 /ask，后端 ChatMemory 自动加载历史上下文
     */
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
            Map<String, String> item = new HashMap<>();
            item.put("role", msg.getMessageType() == MessageType.USER ? "user" : "ai");
            item.put("content", msg.getText());
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
