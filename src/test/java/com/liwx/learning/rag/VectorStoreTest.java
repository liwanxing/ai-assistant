package com.liwx.learning.rag;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * VectorStore 存取测试：验证 Milvus 存入向量 + 相似度检索
 * 这是 RAG 的核心环节：
 * 1. 把几条文本通过 EmbeddingModel 转成向量，存进 Milvus（VectorStore.add 自动完成转向量+存储）
 * 2. 用一个问题也转成向量，去 Milvus 里搜语义最接近的文本（VectorStore.similaritySearch）
 * 前置条件：
 * 1. Milvus 容器已启动
 * 2. 通义 DashScope API Key 有效（embedding 会调通义接口）
 *
 * @see VectorStore
 */
@SpringBootTest
@Tag("integration")  // 集成测试：需 Milvus + API Key，mvn test 默认排除，手动跑：mvn test -Dgroups=integration
class VectorStoreTest {

    @Autowired
    private VectorStore vectorStore;

    /**
     * 第 3 步：往 Milvus 存入 3 条假数据
     * VectorStore.add() 内部自动做了两件事：
     * 1. 调 EmbeddingModel 把每条文本转成向量
     * 2. 把向量 + 原文 + 元数据一起存进 Milvus
     * 注意：重复运行会追加重复数据，学习阶段暂时不管。后续可加 deleteByFilter 清理。
     */
    @Test
    void shouldStoreDocumentsWhenAddToVectorStore() {
        // 构造 3 条假数据，模拟"员工手册"的知识库内容
        // Document 第二个参数是 metadata（元数据），可以记录来源、标题等，检索时能拿到
        List<Document> documents = List.of(
                new Document("请假需要提前三天向直属领导提交申请，经审批后方可休假。",
                        Map.of("source", "员工手册", "chapter", "请假制度")),
                new Document("年假每年有5天，未休完的可以累积到下一年，但最多不超过10天。",
                        Map.of("source", "员工手册", "chapter", "年假制度")),
                new Document("迟到每次扣除50元，一个月内迟到3次以上取消当月全勤奖。",
                        Map.of("source", "考勤制度", "chapter", "迟到处罚"))
        );

        // 存入 Milvus：VectorStore 会自动调 EmbeddingModel 把文本转成向量再存储
        vectorStore.add(documents);

        System.out.println("========== 存入向量测试 ==========");
        System.out.println("成功存入 " + documents.size() + " 条文档");
        System.out.println("==================================");

        // 能走到这里不报错，说明 Milvus 连接、embedding、存储整条链路都通了
        assertNotNull(documents, "文档列表不应为 null");
    }

    /**
     * 第 4 步：从 Milvus 做相似度检索
     * 输入一个问题，VectorStore 会：
     * 1. 把问题转成向量
     * 2. 去 Milvus 里算每个向量和这个问题的"语义距离"
     * 3. 按距离从小到大（越相似越靠前）返回 Top-K 条
     * 依赖：需要先跑 shouldStoreDocumentsWhenAddToVectorStore 存入数据。
     * 如果单独跑这个测试发现搜不到结果，先跑上面那个存入测试。
     */
    @Test
    void shouldReturnRelevantDocsWhenSimilaritySearch() {
        String question = "请假怎么请？";

        // 构建检索请求：指定查询内容和返回条数
        SearchRequest request = SearchRequest.builder()
                .query(question)
                .topK(2)     // 返回最相似的 2 条
                .build();

        // 执行检索
        List<Document> results = vectorStore.similaritySearch(request);

        System.out.println("========== 向量检索测试 ==========");
        System.out.println("问题：" + question);
        System.out.println("检索到 " + results.size() + " 条结果：");
        for (int i = 0; i < results.size(); i++) {
            Document doc = results.get(i);
            System.out.printf("  [%d] %s%n", i + 1, doc.getText());
            System.out.printf("      元数据：%s%n", doc.getMetadata());
        }
        System.out.println("==================================");

        assertNotNull(results, "结果不应为 null");
        assertFalse(results.isEmpty(), "应该能检索到相关文档");
    }
}
