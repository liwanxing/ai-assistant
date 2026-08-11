package com.liwx.learning.rag.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Rerank 重排序服务
 * 为什么需要 Rerank：
 * 向量检索（Milvus）用的是"语义相似度"，可能召回语义相近但不是最相关的结果。
 * Rerank 模型专门做"相关性判断"，对每个候选结果重新打分，把最相关的排到前面。
 * 流程对比：
 * 不用 Rerank：用户问题 → Milvus 检索 topK=3 → 直接给大模型
 * 用 Rerank：用户问题 → Milvus 检索 topK=10 → Rerank 重排序 → 取最相关 topK=3 → 给大模型
 * 通义 Rerank API 不是 OpenAI 兼容格式，是 DashScope 自己的接口，需要手动调 HTTP
 */
@Service
public class RerankService {

    private static final Logger log = LoggerFactory.getLogger(RerankService.class);

    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${spring.ai.openai.api-key}")
    private String apiKey;

    @Value("${rag.rerank.model:gte-rerank-v2}")
    private String rerankModel;

    /**
     * 对检索结果重排序
     *
     * @param query     用户的问题
     * @param documents Milvus 检索到的候选文档（建议 10 条左右）
     * @param topN      重排序后取前 N 条
     * @return 按相关性从高到低排列的文档列表
     */
    @SuppressWarnings("unchecked")
    public List<Document> rerank(String query, List<Document> documents, int topN) {
        if (documents == null || documents.isEmpty()) {
            return List.of();
        }

        try {
            // 1. 构造请求
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.set("Authorization", "Bearer " + apiKey);

            List<String> docTexts = documents.stream().map(Document::getText).toList();
            Map<String, Object> body = Map.of(
                    "model", rerankModel,
                    "input", Map.of(
                            "query", query,
                            "documents", docTexts
                    ),
                    "parameters", Map.of(
                            "top_n", topN,
                            "return_documents", false
                    )
            );

            // 2. 调用通义 Rerank API
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);
            String rerankUrl = "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";
            log.info("Rerank 请求: query={}, 候选文档={} 条, topN={}", query, documents.size(), topN);

            ResponseEntity<Map> response = restTemplate.exchange(rerankUrl, HttpMethod.POST, entity, Map.class);
            Map<String, Object> responseBody = response.getBody();

            if (responseBody == null) {
                log.warn("Rerank 返回空，使用原始顺序的前 {} 条", topN);
                return documents.subList(0, Math.min(topN, documents.size()));
            }

            // 3. 解析结果：output.results 是一个数组，每项含 index（原文档索引）和 relevance_score（相关性分数）
            Map<String, Object> output = (Map<String, Object>) responseBody.get("output");
            List<Map<String, Object>> results = (List<Map<String, Object>>) output.get("results");

            // 4. 按 Rerank 返回的 index 取原始文档，组装结果
            List<Document> reranked = new ArrayList<>();
            for (Map<String, Object> result : results) {
                int index = ((Number) result.get("index")).intValue();
                double score = ((Number) result.get("relevance_score")).doubleValue();
                Document doc = documents.get(index);
                doc.getMetadata().put("rerank_score", score);  // 记录分数到 metadata
                reranked.add(doc);
                log.info("Rerank 结果: index={}, score={}", index, score);
            }

            log.info("Rerank 完成: {} 条候选 → {} 条精选", documents.size(), reranked.size());
            return reranked;

        } catch (Exception e) {
            log.error("Rerank 调用失败，降级使用原始检索顺序: {}", e.getMessage());
            // 降级策略：Rerank 失败不影响服务，用原始检索结果的前 topN 条
            return documents.subList(0, Math.min(topN, documents.size()));
        }
    }
}
