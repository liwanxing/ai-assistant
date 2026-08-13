package com.liwx.learning.agent.tool;

import com.liwx.learning.rag.mapper.RagChunkMapper;
import com.liwx.learning.rag.service.RerankService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 知识库检索工具（混合检索）
 *
 * 检索策略：向量检索（语义相似）+ 关键词检索（精确匹配）→ 合并去重 → Rerank 重排
 *
 * 为什么需要混合检索：
 *   纯向量检索：擅长语义匹配（"怎么休假" 能匹配到 "请假流程"），但对专有名词、编号容易漏
 *   纯关键词检索：擅长精确匹配（搜 "ISO9001" 直接命中），但不理解同义词
 *   两者互补：向量管"意思接近"，关键词管"字面包含"，Rerank 负责统一排序
 *
 * 这个类把原来 RagAdvisor 里「每次都执行」的检索逻辑，封装成一个 @Tool
 * 区别：
 *   RagAdvisor（旧）：每个请求都走向量检索，模型管不了
 *   RagTool（新）：模型看到 @Tool 的 description 后自己决定是否调用
 */
@Slf4j
@Component
public class RagTool {

    private final VectorStore vectorStore;
    private final RerankService rerankService;
    private final RagChunkMapper ragChunkMapper;

    public RagTool(VectorStore vectorStore, RerankService rerankService, RagChunkMapper ragChunkMapper) {
        this.vectorStore = vectorStore;
        this.rerankService = rerankService;
        this.ragChunkMapper = ragChunkMapper;
    }

    /**
     * 混合检索：向量 + 关键词，合并去重后 Rerank
     * description 是给模型看的——模型通过这段描述决定何时调用此工具
     */
    @Tool(description = "搜索知识库，查找与用户问题相关的文档内容。当用户询问公司制度、产品信息、操作指南等需要查阅资料的问题时调用此工具。闲聊或常识问题不需要调用。")
    public String searchKnowledge(
            @ToolParam(description = "用户的问题或搜索关键词") String query
    ) {
        log.info("RagTool 混合检索，查询：{}", query);

        try {
            // 1. 向量检索（Milvus）：语义相似 top10
            List<Document> vectorResults = vectorStore.similaritySearch(SearchRequest.builder()
                    .query(query)
                    .topK(10)
                    .build());
            log.info("向量检索命中 {} 条", vectorResults.size());

            // 2. 关键词检索（MySQL FULLTEXT）：精确匹配 top10
            List<Map<String, Object>> keywordResults = ragChunkMapper.searchByKeyword(query, 10);
            log.info("关键词检索命中 {} 条", keywordResults.size());

            // 3. 合并去重（用 chunk_id 或文本内容做唯一键）
            // LinkedHashMap 保持插入顺序，value 是是否来自关键词检索（用于日志标注来源）
            Map<String, Document> merged = new LinkedHashMap<>();
            for (Document doc : vectorResults) {
                merged.put(doc.getId(), doc);
            }
            for (Map<String, Object> row : keywordResults) {
                String chunkId = (String) row.get("chunk_id");
                if (!merged.containsKey(chunkId)) {
                    // 关键词命中但向量没命中的 chunk，补进来
                    merged.put(chunkId, Document.builder()
                            .id(chunkId)
                            .text((String) row.get("content"))
                            .build());
                }
            }

            List<Document> allCandidates = new ArrayList<>(merged.values());
            if (allCandidates.isEmpty()) {
                return "未找到相关资料";
            }

            log.info("合并去重后 {} 条候选（向量 {} + 关键词新增 {}）",
                    allCandidates.size(), vectorResults.size(), allCandidates.size() - vectorResults.size());

            // 4. Rerank 重排序：从全部候选中取最相关的 3 条
            List<Document> reranked = rerankService.rerank(query, allCandidates, 3);

            // 5. 拼接成文本返回给模型
            StringBuilder result = new StringBuilder();
            for (int i = 0; i < reranked.size(); i++) {
                result.append("【参考资料").append(i + 1).append("】\n")
                        .append(reranked.get(i).getText())
                        .append("\n\n");
            }

            log.info("RagTool 返回 {} 条参考资料（混合检索）", reranked.size());
            return result.toString();

        } catch (Exception e) {
            log.warn("RagTool 混合检索失败：{}", e.getMessage());
            return "知识库检索失败，请尝试直接回答";
        }
    }
}
