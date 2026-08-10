package com.liwx.learning.rag.controller;

import com.liwx.learning.common.Result;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
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
