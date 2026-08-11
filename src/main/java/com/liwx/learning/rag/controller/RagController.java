package com.liwx.learning.rag.controller;

import com.liwx.learning.common.Result;
import com.liwx.learning.rag.entity.RagDocument;
import com.liwx.learning.rag.mapper.RagDocumentMapper;
import com.liwx.learning.rag.service.RagService;
import com.liwx.learning.rag.service.RerankService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
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
 * <p>
 * 完整链路：用户提问 → 向量检索 Milvus → 拼接检索结果到 prompt → 通义大模型生成回答
 * <p>
 * 和 AiController 的区别：
 * AiController 是直接问大模型，模型只用自己训练时学到的知识回答（不知道你公司的文档）
 * RAG 是先检索你的知识库，把相关内容塞给大模型，让它基于你的资料回答
 */
@RestController
@RequestMapping("/rag")
public class RagController {

    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final RagService ragService;
    private final RerankService rerankService;
    private final RagDocumentMapper ragDocumentMapper;

    public RagController(ChatClient chatClient, VectorStore vectorStore,
                         RagService ragService, RerankService rerankService,
                         RagDocumentMapper ragDocumentMapper) {
        this.chatClient = chatClient;
        this.vectorStore = vectorStore;
        this.ragService = ragService;
        this.rerankService = rerankService;
        this.ragDocumentMapper = ragDocumentMapper;
    }

    /**
     * 文档上传（异步处理）：保存文件 → 插表(PROCESSING) → 立即返回 → 后台异步切分+向量化
     * <p>
     * 和之前同步上传的区别：
     * - 旧：上传后等十几秒（解析+切分+向量化），用户一直等
     * - 新：上传后立即返回"处理中"，后台异步处理，前端轮询状态
     */
    @PostMapping("/upload")
    public Result<Map<String, Object>> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "splitStrategy", defaultValue = "token") String splitStrategy) throws Exception {
        // 1. 保存文件到本地 uploads/ 目录
        // 用 UUID 重命名防止同名文件覆盖
        // 必须用绝对路径：transferTo 传相对路径时，Tomcat 会解析到自己的临时目录下
        String originalName = file.getOriginalFilename();
        String ext = originalName.substring(originalName.lastIndexOf("."));
        String storedName = UUID.randomUUID() + ext;

        Path uploadDir = Path.of("uploads").toAbsolutePath();
        Files.createDirectories(uploadDir);
        Path dest = uploadDir.resolve(storedName);
        file.transferTo(dest.toFile());

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
     * RAG 问答（流式）：根据知识库内容回答用户问题，AI 回答逐字输出
     * <p>
     * 用法：GET /rag/ask?question=请假怎么请？
     * 返回格式：text/event-stream（SSE），每条消息格式为 data:文字片段\n\n
     * <p>
     * 和同步返回的区别：.call() 等全部生成完再返回，.stream() 生成一个 token 就推一个
     */
    @GetMapping(value = "/ask", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> ask(@RequestParam String question) {
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

        // 4. 流式生成：.stream() 替代 .call()，返回 Flux<String>
        // 大模型每生成一个 token 就通过 SSE 推送一次，不用等全部生成完
        // concatWithValues("[DONE]")：流结束时追加一个结束标记，和 OpenAI 的做法一样
        return chatClient.prompt()
                .system("你是一个知识库问答助手。请根据以下参考资料回答用户的问题。" +
                        "回答时请在引用的内容后面标注来源，格式如[1]、[2]，对应参考资料的编号。" +
                        "如果参考资料中没有相关信息，请明确告知'根据现有资料无法回答此问题'，不要编造。")
                .user("参考资料：\n" + contextBuilder + "\n\n问题：" + question)
                .stream()
                .content()
                .concatWithValues("[DONE]");
    }
}
