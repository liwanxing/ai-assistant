package com.liwx.learning.agent.tool;

import com.liwx.learning.rag.service.RerankService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * RAG 知识库检索工具
 *
 * 这个类把原来 RagAdvisor 里「每次都执行」的检索逻辑，封装成一个 @Tool
 * 区别：
 *   RagAdvisor（旧）：每个请求都走向量检索，模型管不了
 *   RagTool（新）：模型看到 @Tool 的 description 后自己决定是否调用
 *     用户问"请假怎么请" → 模型判断需要查知识库 → 调用这个工具
 *     用户问"你好"       → 模型判断不需要查    → 不调用，直接回答
 */
@Slf4j
@Component
public class RagTool {

    private final VectorStore vectorStore;
    private final RerankService rerankService;

    public RagTool(VectorStore vectorStore, RerankService rerankService) {
        this.vectorStore = vectorStore;
        this.rerankService = rerankService;
    }

    /**
     * 搜索知识库，返回最相关的参考资料
     * description 是给模型看的——模型通过这段描述决定何时调用此工具
     */
    @Tool(description = "搜索知识库，查找与用户问题相关的文档内容。当用户询问公司制度、产品信息、操作指南等需要查阅资料的问题时调用此工具。闲聊或常识问题不需要调用。")
    public String searchKnowledge(
            @ToolParam(description = "用户的问题或搜索关键词") String query
    ) {
        log.info("RagTool 被调用，查询：{}", query);

        try {
            // 1. 向量检索：从 Milvus 多召回候选
            List<Document> documents = vectorStore.similaritySearch(SearchRequest.builder()
                    .query(query)
                    .topK(10)
                    .build());

            if (documents.isEmpty()) {
                return "未找到相关资料";
            }

            // 2. Rerank 重排序：取最相关的 3 条
            documents = rerankService.rerank(query, documents, 3);

            // 3. 拼接成文本返回给模型
            StringBuilder result = new StringBuilder();
            for (int i = 0; i < documents.size(); i++) {
                result.append("【参考资料").append(i + 1).append("】\n")
                        .append(documents.get(i).getText())
                        .append("\n\n");
            }

            log.info("RagTool 返回 {} 条参考资料", documents.size());
            return result.toString();

        } catch (Exception e) {
            log.warn("RagTool 检索失败：{}", e.getMessage());
            return "知识库检索失败，请尝试直接回答";
        }
    }
}
