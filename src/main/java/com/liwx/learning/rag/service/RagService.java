package com.liwx.learning.rag.service;

import com.liwx.learning.ai.advisor.core.SemanticCacheStore;
import com.liwx.learning.rag.entity.RagDocument;
import com.liwx.learning.rag.enums.SplitStrategy;
import com.liwx.learning.rag.mapper.RagChunkMapper;
import com.liwx.learning.rag.mapper.RagDocumentMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * RAG 文档异步处理服务
 * 为什么用 @Async：
 * 文档解析 + 切分 + 向量化加起来可能要十几秒，改为异步后上传接口立即返回，后台处理完更新数据库状态。
 * 注意：@Async 方法必须和调用者不在同一个类，否则 Spring 的 AOP 代理不生效
 */
@Slf4j
@Service
public class RagService {

    private final RagDocumentMapper ragDocumentMapper;

    private final RagChunkMapper ragChunkMapper;

    /**
     * 向量数据库（本项目用 Milvus）
     * 怎么理解向量数据库：
     * 1. 向量不是它生成的：文本转向量是 EmbeddingModel（通义 text-embedding-v3）干的，
     *    Milvus 只负责存储。存储本身任何数据库都能做（MySQL、Redis 都能存 float 数组）。
     * 2. 核心价值在检索：普通数据库做精确匹配（WHERE id=1），向量数据库做相似度检索
     *    （"和这个问题语义最接近的 10 条记录"），内部用 HNSW 图索引加速，毫秒级返回。
     * 3. 存的不是表格：存的是 1024 维浮点数数组 + 元数据，为向量计算专门优化了存储结构。
     * 一句话：向量数据库 = 存向量的仓库 + 快速找相似向量的索引引擎
     */
    private final VectorStore vectorStore;
    private final EmbeddingModel embeddingModel;

    /**
     * 语义缓存：文档变更后全量失效（旧答案基于旧知识库，不清缓存会一直返回过期答案）
     */
    private final SemanticCacheStore semanticCacheStore;

    @Value("${rag.retry.max-attempts:3}")
    private int maxAttempts;

    @Value("${rag.retry.delay-ms:5000}")
    private long retryDelayMs;

    public RagService(RagDocumentMapper ragDocumentMapper, RagChunkMapper ragChunkMapper,
                      VectorStore vectorStore, EmbeddingModel embeddingModel,
                      SemanticCacheStore semanticCacheStore) {
        this.ragDocumentMapper = ragDocumentMapper;
        this.ragChunkMapper = ragChunkMapper;
        this.vectorStore = vectorStore;
        this.embeddingModel = embeddingModel;
        this.semanticCacheStore = semanticCacheStore;
    }

    /**
     * 异步处理文档：读取 → 切分 → 给 chunk 设置 ID → 向量化 → 存 Milvus → 更新状态
     * chunk ID 格式：doc{documentId}_{index}
     * 为什么要自定义 ID：Spring AI 默认给每个 chunk 生成随机 UUID，
     * 和 MySQL 的 documentId 没有关联，删除文档时无法定位 Milvus 里的 chunk。
     * 用 doc{documentId}_{index} 后，删除时可以精确构造 ID 列表批量删除。
     *
     * @param splitStrategy 切分策略：TOKEN / PARAGRAPH / SEMANTIC
     */
    @Async
    public void processDocument(Long documentId, String filePath, SplitStrategy splitStrategy) {
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                log.info("开始处理文档, documentId={}, attempt={}/{}, splitStrategy={}",
                        documentId, attempt, maxAttempts, splitStrategy);

                int chunkCount = doProcessDocument(documentId, filePath, splitStrategy);

                // 成功：更新状态，退出循环
                ragDocumentMapper.updateStatus(documentId, "SUCCESS", chunkCount, null);
                // 知识库变了，基于旧知识库的缓存答案全部作废
                semanticCacheStore.invalidate();
                log.info("文档处理完成, documentId={}, chunks={}", documentId, chunkCount);
                return;

            } catch (Exception e) {
                log.warn("文档处理失败, documentId={}, attempt={}/{}, error={}",
                        documentId, attempt, maxAttempts, e.getMessage());

                if (attempt < maxAttempts) {
                    // 还有机会重试：等待间隔后再试
                    log.info("等待 {}ms 后重试...", retryDelayMs);
                    try {
                        Thread.sleep(retryDelayMs);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        ragDocumentMapper.updateStatus(documentId, "FAILED", 0, "线程被中断");
                        return;
                    }
                } else {
                    // 最后一次也失败了，标记为 FAILED
                    log.error("文档处理最终失败, documentId={}", documentId, e);
                    ragDocumentMapper.updateStatus(documentId, "FAILED", 0, e.getMessage());
                }
            }
        }
    }

    /**
     * 实际的文档处理逻辑：读取 → 切分 → 设ID → 向量化 → 存 Milvus
     *
     * @return 切分后的 chunk 数量
     * @throws Exception 任何步骤失败都抛异常，由上层 processDocument 决定是否重试
     */
    private int doProcessDocument(Long documentId, String filePath, SplitStrategy splitStrategy) throws Exception {
        // 1. Tika 读取文件内容（自动识别 PDF/Word/txt 格式）
        // 这是生产级方案：Tika 提取纯文本，格式信息（标题/字号）会丢失，但配合切分策略足以覆盖绝大多数场景。
        // 如果文档格式较差（扫描件或无段落结构），用户可在上传时选择「语义切分」来弥补，
        // 语义切分不依赖格式，通过 embedding 相似度自动识别话题边界，代价是多耗一些 API 调用。
        // 早期 RAG 需要复杂的 PDF 结构化解析（提取标题层级、字号、版面布局），有了语义切分后这些方案已被淘汰。
        TikaDocumentReader reader = new TikaDocumentReader(new FileSystemResource(filePath));
        List<Document> documents = reader.get();

        // 2. 根据策略切分
        List<Document> chunks;
        switch (splitStrategy) {
            case SEMANTIC -> chunks = splitBySemantic(documents);
            case PARAGRAPH -> chunks = splitByParagraph(documents);
            default -> chunks = TokenTextSplitter.builder().build().apply(documents);
        }

        // 3. 给每个 chunk 设置自定义 ID（doc{documentId}_{index}）
        // Document 是不可变对象，没有 setId，只能用 builder 重新构建，把原文和元数据拷贝过去
        List<Document> namedChunks = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            Document original = chunks.get(i);
            Document named = Document.builder()
                    .id("doc" + documentId + "_" + i)
                    .text(original.getText())
                    .metadata(original.getMetadata())
                    .build();
            namedChunks.add(named);
        }

        // 4. 分批向量化并存入 Milvus（通义 API 每次最多 10 条，Spring AI 内部也会调 embedding）
        int batchSize = 10;
        for (int i = 0; i < namedChunks.size(); i += batchSize) {
            int end = Math.min(i + batchSize, namedChunks.size());
            vectorStore.add(namedChunks.subList(i, end));
        }

        // 5. 同时存 MySQL（带 FULLTEXT INDEX，供关键词检索用）
        // 双写：Milvus 存向量做语义检索，MySQL 存原文做关键词检索
        List<Map<String, Object>> chunkRows = new ArrayList<>();
        for (int i = 0; i < namedChunks.size(); i++) {
            Map<String, Object> row = new HashMap<>();
            row.put("documentId", documentId);
            row.put("chunkId", "doc" + documentId + "_" + i);
            row.put("chunkIndex", i);
            row.put("content", namedChunks.get(i).getText());
            chunkRows.add(row);
        }
        ragChunkMapper.batchInsert(chunkRows);

        return namedChunks.size();
    }

    /**
     * 段落切分：先按换行分段，再把相邻小段合并到不超过 token 上限
     * 和 TokenTextSplitter 的区别：
     * - Token 硬切：到 token 数就断，可能切断句子（如"审批流|程是"）
     * - 段落切分：以换行为天然边界，保持每个 chunk 语义完整
     * 适用场景：规章制度、合同条款等有明确段落结构的文档
     */
    private List<Document> splitByParagraph(List<Document> documents) {
        List<Document> result = new ArrayList<>();
        // 每个 chunk 最多 500 token（Spring AI 默认约 800，段落切分保守一点）
        int maxTokens = 500;

        for (Document doc : documents) {
            String text = doc.getText();
            // 按双换行（空行）分段，这是最常见的段落分隔方式
            String[] paragraphs = text.split("\\n\\s*\\n");

            StringBuilder currentChunk = new StringBuilder();
            int currentTokens = 0;

            for (String paragraph : paragraphs) {
                String trimmed = paragraph.trim();
                if (trimmed.isEmpty()) continue;

                // 粗略估算 token 数：中文约 1 字 = 2 token，英文约 1 词 = 1.3 token
                int paraTokens = trimmed.length() * 2;

                // 如果当前 chunk 加上这段会超限，先保存当前 chunk，再开新的
                if (currentTokens + paraTokens > maxTokens && currentChunk.length() > 0) {
                    result.add(Document.builder()
                            .text(currentChunk.toString().trim())
                            .metadata(doc.getMetadata())
                            .build());
                    currentChunk = new StringBuilder();
                    currentTokens = 0;
                }

                currentChunk.append(trimmed).append("\n\n");
                currentTokens += paraTokens;
            }

            // 最后一个 chunk
            if (currentChunk.length() > 0) {
                result.add(Document.builder()
                        .text(currentChunk.toString().trim())
                        .metadata(doc.getMetadata())
                        .build());
            }
        }

        return result;
    }

    /**
     * 语义切分：把文本拆成句子，用 embedding 计算相邻句子的语义相似度，相似度骤降处切开
     * 原理：相邻句子的话题变化时，它们的向量相似度会明显下降。
     * 通过计算每对相邻句子的余弦相似度，找到"话题切换点"。
     * 代价：每个句子都要调 embedding API，比前两种方式慢得多，但切分效果最好。
     * 适合对检索质量要求高的场景。
     */
    private List<Document> splitBySemantic(List<Document> documents) {
        List<Document> result = new ArrayList<>();
        // 相似度低于此值认为话题切换（范围 -1~1，经验值 0.5）
        double similarityThreshold = 0.5;

        for (Document doc : documents) {
            String text = doc.getText();

            // 1. 拆成句子（中文。！？和英文.!?）
            List<String> sentences = new ArrayList<>();
            String[] parts = text.split("(?<=[。！？.!?:：])");
            for (String part : parts) {
                String trimmed = part.trim();
                if (!trimmed.isEmpty()) {
                    sentences.add(trimmed);
                }
            }
            if (sentences.size() <= 1) {
                result.add(doc);
                continue;
            }

            // 2. 分批计算 embedding（通义 DashScope 限制每次最多 10 条）
            log.info("语义切分: {} 个句子，正在分批计算向量...", sentences.size());
            List<float[]> embeddings = new ArrayList<>();
            int batchSize = 10;
            for (int i = 0; i < sentences.size(); i += batchSize) {
                int end = Math.min(i + batchSize, sentences.size());
                embeddings.addAll(embeddingModel.embed(sentences.subList(i, end)));
            }

            // 3. 逐对比较相邻句子的相似度，在话题切换处切开
            List<String> currentChunk = new ArrayList<>();
            currentChunk.add(sentences.get(0));

            for (int i = 1; i < sentences.size(); i++) {
                // 计算余弦相似度（值域 -1~1，越接近 1 表示语义越相似）
                float[] vecA = embeddings.get(i - 1);
                float[] vecB = embeddings.get(i);
                float dot = 0, normA = 0, normB = 0;
                for (int j = 0; j < vecA.length; j++) {
                    dot += vecA[j] * vecB[j];
                    normA += vecA[j] * vecA[j];
                    normB += vecB[j] * vecB[j];
                }
                double similarity = dot / (Math.sqrt(normA) * Math.sqrt(normB));

                if (similarity < similarityThreshold && currentChunk.size() >= 2) {
                    // 话题变了，保存当前 chunk
                    result.add(Document.builder()
                            .text(String.join("", currentChunk))
                            .metadata(doc.getMetadata())
                            .build());
                    currentChunk = new ArrayList<>();
                }
                currentChunk.add(sentences.get(i));
            }

            // 最后一个 chunk
            if (!currentChunk.isEmpty()) {
                result.add(Document.builder()
                        .text(String.join("", currentChunk))
                        .metadata(doc.getMetadata())
                        .build());
            }
        }

        log.info("语义切分完成，共 {} 个 chunk", result.size());
        return result;
    }

    /**
     * 删除文档：删 Milvus 向量 → 删本地文件 → 软删除 MySQL 记录
     */
    public void deleteDocument(Long documentId) {
        RagDocument doc = ragDocumentMapper.selectById(documentId);
        if (doc == null) return;

        // 1. 删除 Milvus 中的向量（只有处理成功的文档才有向量）
        if ("SUCCESS".equals(doc.getStatus()) && doc.getChunkCount() != null && doc.getChunkCount() > 0) {
            List<String> ids = new ArrayList<>();
            for (int i = 0; i < doc.getChunkCount(); i++) {
                ids.add("doc" + documentId + "_" + i);
            }
            vectorStore.delete(ids);
            log.info("已删除 Milvus 向量, documentId={}, chunks={}", documentId, ids.size());
        }

        // 2. 删除本地文件
        try {
            Files.deleteIfExists(Path.of(doc.getFilePath()));
        } catch (Exception e) {
            log.warn("删除本地文件失败: {}", doc.getFilePath(), e);
        }

        // 3. 删除 MySQL 分段记录
        ragChunkMapper.deleteByDocumentId(documentId);

        // 4. 软删除 MySQL 文档记录
        ragDocumentMapper.deleteById(documentId);

        // 5. 知识库变了，语义缓存里基于该知识库的答案全部作废
        semanticCacheStore.invalidate();
        log.info("文档已删除, documentId={}", documentId);
    }
}
