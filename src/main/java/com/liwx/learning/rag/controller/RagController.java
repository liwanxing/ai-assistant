package com.liwx.learning.rag.controller;

import com.liwx.learning.common.Result;
import com.liwx.learning.rag.entity.RagDocument;
import com.liwx.learning.rag.enums.SplitStrategy;
import com.liwx.learning.rag.mapper.RagDocumentMapper;
import com.liwx.learning.rag.service.RagService;
import com.liwx.learning.rag.service.RerankService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import reactor.core.publisher.Flux;

import java.nio.file.Files;
import java.nio.file.Path;
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
            @RequestParam(value = "splitStrategy", defaultValue = "token") SplitStrategy splitStrategy) throws Exception {
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
    public Flux<String> ask(@RequestParam String question, @RequestParam String sessionId) {
        // 1. 向量检索：先从 Milvus 多召回一些候选（topK=10），给 Rerank 留筛选空间
        List<Document> documents = vectorStore.similaritySearch(SearchRequest.builder()
                .query(question)
                .topK(10)
                .build());

        // 2. Rerank 重排序：对 10 条候选用专门的排序模型重新打分，取最相关的 3 条
        // 向量检索是"语义相似度"，Rerank 是"相关性判断"，两者配合效果最好
        if (!documents.isEmpty()) {
            documents = rerankService.rerank(question, documents, 3);
        }

        // 3. 把搜到的文档内容带编号拼接，方便大模型引用时标注来源
        // 格式：【参考资料1】内容...  【参考资料2】内容...
        StringBuilder contextBuilder = new StringBuilder();
        for (int i = 0; i < documents.size(); i++) {
            contextBuilder.append("【参考资料").append(i + 1).append("】\n")
                    .append(documents.get(i).getText())
                    .append("\n\n");
        }

        // 4. 流式生成 + 多轮记忆：advisors 传入 sessionId，Advisor 自动从 MySQL 加载历史拼到 prompt 里
        return chatClient.prompt()
                .system("你是一个知识库问答助手。请根据以下参考资料回答用户的问题。" +
                        "回答时请在引用的内容后面标注来源，格式如[1]、[2]，对应参考资料的编号。" +
                        "如果参考资料中没有相关信息，请明确告知'根据现有资料无法回答此问题'，不要编造。")
                .user("参考资料：\n" + contextBuilder + "\n\n问题：" + question)
                .advisors(a -> a.param(ChatMemory.CONVERSATION_ID, sessionId))
                .stream()
                .content()
                .concatWithValues("[DONE]");
    }
}
