package com.liwx.learning.rag;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Embedding 模型测试：验证文本转向量（Embedding）功能
 * 包路径 com.liwx.learning.rag 与源码中的 rag 模块对应。
 * Embedding 是 RAG 的基础能力：
 * 把人类理解的文字 → 转成机器理解的数字向量（1024 维），
 * 语义相近的文本，向量距离也近，这是后续向量检索的前提。
 * 前置条件：
 * 1. Milvus 容器已启动（@SpringBootTest 会加载完整上下文，含 MilvusVectorStore 自动配置）
 * 2. 通义 DashScope API Key 有效（application.yml 中配置）
 *
 * @see org.springframework.ai.embedding.EmbeddingModel
 */
@SpringBootTest
@Tag("integration")  // 集成测试：需 Milvus + API Key，mvn test 默认排除，手动跑：mvn test -Dgroups=integration
class EmbeddingModelTest {

    // EmbeddingModel 的注入原理和 ChatModel 一样：
    // Spring AI 读到 application.yml 里的 spring.ai.openai.embedding.model 配置后，
    // 自动创建 OpenAiEmbeddingModel Bean（底层发 HTTP 请求到百炼的 embedding 接口）。
    // 你不需要手动 new，直接注入就能用。
    @Autowired
    private EmbeddingModel embeddingModel;

    /**
     * 验证文本转向量：一段中文 → 1024 维浮点数组
     * 验证点：
     * - 返回的向量不为 null（说明通义 embedding 接口调通了）
     * - 维度等于 1024（text-embedding-v3 的输出维度）
     */
    @Test
    void shouldReturnVectorWhenEmbedText() {
        String text = "请假需要提前三天申请";

        float[] vector = embeddingModel.embed(text);

        System.out.println("========== 文本转向量测试 ==========");
        System.out.println("原始文本：" + text);
        System.out.println("向量维度：" + vector.length);

        System.out.print("前 5 个维度的值：");
        for (int i = 0; i < 5; i++) {
            System.out.printf("%.4f  ", vector[i]);
        }
        System.out.println();
        System.out.println("====================================");

        assertNotNull(vector, "向量不应为 null");
        assertEquals(1024, vector.length, "text-embedding-v3 输出维度应为 1024");
    }
}
