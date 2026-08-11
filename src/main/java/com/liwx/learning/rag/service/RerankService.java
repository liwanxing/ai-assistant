package com.liwx.learning.rag.service;

import lombok.extern.slf4j.Slf4j;
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
import java.util.HashMap;
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
@Slf4j
@Service
public class RerankService {

    private final RestTemplate restTemplate = new RestTemplate();

    @Value("${spring.ai.openai.api-key}")
    private String apiKey;

    @Value("${rag.rerank.model:gte-rerank-v2}")
    private String rerankModel;

    /**
     * 调用通义 Rerank API
     * {
     *   "model": "gte-rerank-v2",
     *   "input": {
     *     "query": "用户的问题",
     *     "documents": ["文档1内容", "文档2内容", ...]
     *   },
     *   "parameters": {
     *     "top_n": 3,
     *     "return_documents": false
     *   }
     * }
     * {
     *   "output": {
     *     "results": [
     *       {"index": 2, "relevance_score": 0.95},
     *       {"index": 0, "relevance_score": 0.87}
     *     ]
     *   }
     * }
     */
    @SuppressWarnings("unchecked")
    private ResponseEntity<Map> callRerankApi(String query, List<String> docTexts, int topN) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Authorization", "Bearer " + apiKey);

        Map<String, Object> body = new HashMap<>();
        body.put("model", rerankModel);

        Map<String, Object> input = new HashMap<>();
        input.put("query", query);
        input.put("documents", docTexts);
        body.put("input", input);

        Map<String, Object> parameters = new HashMap<>();
        parameters.put("top_n", topN);
        parameters.put("return_documents", false);
        body.put("parameters", parameters);

        String rerankUrl = "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank";
        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

        log.info("Rerank 请求: query={}, 候选文档={} 条, topN={}", query, docTexts.size(), topN);
        return restTemplate.exchange(rerankUrl, HttpMethod.POST, entity, Map.class);
    }

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
            // 1. 调用 Rerank API
            List<String> docTexts = documents.stream().map(Document::getText).toList();
            ResponseEntity<Map> response = callRerankApi(query, docTexts, topN);
            Map<String, Object> responseBody = response.getBody();

            if (responseBody == null) {
                log.warn("Rerank 返回空，使用原始顺序的前 {} 条", topN);
                return documents.subList(0, Math.min(topN, documents.size()));
            }

            // 2. 按 index 取原始文档，记录分数
            Map<String, Object> output = (Map<String, Object>) responseBody.get("output");
            List<Map<String, Object>> results = (List<Map<String, Object>>) output.get("results");

            List<Document> reranked = new ArrayList<>();
            for (Map<String, Object> result : results) {
                int index = ((Number) result.get("index")).intValue();
                double score = ((Number) result.get("relevance_score")).doubleValue();
                Document doc = documents.get(index);
                doc.getMetadata().put("rerank_score", score);
                reranked.add(doc);
            }

            log.info("Rerank 完成: {} 条候选 → {} 条精选", documents.size(), reranked.size());
            return reranked;

        } catch (Exception e) {
            log.error("Rerank 调用失败，降级使用原始检索顺序: {}", e.getMessage());
            return documents.subList(0, Math.min(topN, documents.size()));
        }
    }
}
