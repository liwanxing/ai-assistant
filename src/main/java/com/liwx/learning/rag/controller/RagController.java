package com.liwx.learning.rag.controller;

import com.liwx.learning.common.Result;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * RAG 问答接口（检索增强生成）
 * <p>
 * 完整链路：用户提问 → 向量检索 Milvus → 拼接检索结果到 prompt → 通义大模型生成回答
 * <p>
 * 和 AiController 的区别：
 * AiController 是直接问大模型，模型只用自己训练时学到的知识回答（不知道你公司的文档）
 * RAG 是先检索你的知识库，把相关内容塞给大模型，让它基于你的资料回答
 *
 * 调用链路：
 *   GET /rag/ask?question=请假怎么请？
 *   → VectorStore 把问题转成向量，去 Milvus 搜最相似的文档
 *   → 把搜到的文档内容拼进 prompt
 *   → ChatClient 把拼好的 prompt 发给通义
 *   → 通义基于你的文档生成有依据的回答
 */
@RestController
@RequestMapping("/rag")
public class RagController {

    private final ChatClient chatClient;
    private final VectorStore vectorStore;

    public RagController(ChatClient.Builder chatClientBuilder, VectorStore vectorStore) {
        this.chatClient = chatClientBuilder.build();
        this.vectorStore = vectorStore;
    }

    /**
     * 文档上传：上传 txt 文件 → 自动切分 → 向量化后存入 Milvus
     * <p>
     * 做的事（同步）：
     * 1. 读取文件内容
     * 2. TokenTextSplitter 按 token 数量切分成多个 chunk（默认每块约 800 token）
     * 3. VectorStore.add 内部自动调 EmbeddingModel 把每个 chunk 转成向量，再存入 Milvus
     * <p>
     * 现阶段用同步处理：txt 文件很小，几秒就处理完。
     * 以后换大文件（PDF 50页）再改异步 + MQ。
     * <p>
     * 用法：POST /rag/upload，form-data 上传文件，字段名 file
     */
    @PostMapping("/upload")
    public Result<Map<String, Integer>> upload(@RequestParam("file") MultipartFile file) throws Exception {
        // 1. 读取文件内容（只支持 txt，后续再加 PDF/Word 解析）
        String content = new String(file.getBytes(), StandardCharsets.UTF_8);

        // 2. 切分：TokenTextSplitter 按 token 数量自动切分（不是按字数，更贴合模型的输入限制）
        // 默认配置：每块最多 800 token，块之间重叠 400 token（重叠是为了保证上下文不丢失）
        TokenTextSplitter splitter = TokenTextSplitter.builder().build();
        List<Document> chunks = splitter.apply(List.of(new Document(content)));

        // 3. 存入 Milvus（VectorStore 内部自动调 EmbeddingModel 转向量）
        vectorStore.add(chunks);

        return Result.success(Map.of("chunkCount", chunks.size()));
    }

    /**
     * RAG 问答：根据知识库内容回答用户问题
     * <p>
     * 用法：GET /rag/ask?question=请假怎么请？
     * 前提：知识库里已经有数据（先跑 VectorStoreTest 的存入测试）
     */
    @GetMapping("/ask")
    public Result<String> ask(@RequestParam String question) {
        // 1. 向量检索：把问题转成向量，去 Milvus 搜最相似的 3 条文档
        List<Document> documents = vectorStore.similaritySearch(SearchRequest.builder()
                .query(question)
                .topK(3)
                .build());

        // 2. 把搜到的文档内容拼接成一段文本，作为大模型的参考资料
        String context = documents.stream()
                .map(Document::getText)
                .collect(Collectors.joining("\n\n"));

        // 3. 构造 RAG prompt，把检索到的资料和用户问题一起发给大模型
        // system：告诉大模型它的角色和规则（必须基于资料回答，不要编造）
        // user：给出参考资料 + 用户的问题
        String answer = chatClient.prompt()
                .system("你是一个知识库问答助手。请根据以下参考资料回答用户的问题。" +
                        "如果参考资料中没有相关信息，请明确告知'根据现有资料无法回答此问题'，不要编造。")
                .user("参考资料：\n" + context + "\n\n问题：" + question)
                .call()
                .content();

        return Result.success(answer);
    }
}
