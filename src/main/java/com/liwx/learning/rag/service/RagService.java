package com.liwx.learning.rag.service;

import com.liwx.learning.rag.entity.RagDocument;
import com.liwx.learning.rag.mapper.RagDocumentMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.reader.tika.TikaDocumentReader;
import org.springframework.ai.transformer.splitter.TokenTextSplitter;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.core.io.FileSystemResource;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * RAG 文档异步处理服务
 * <p>
 * 为什么用 @Async：
 * 文档解析 + 切分 + 向量化（每个 chunk 调一次 embedding API）加起来可能要十几秒。
 * 如果同步执行，用户要一直等。改为异步后，上传接口立即返回"处理中"，
 * 后台线程慢慢处理，处理完更新数据库状态，前端轮询就能看到结果。
 * <p>
 * 注意：@Async 方法必须和调用者不在同一个类，否则 Spring 的 AOP 代理不生效
 */
@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    private final RagDocumentMapper ragDocumentMapper;
    private final VectorStore vectorStore;

    public RagService(RagDocumentMapper ragDocumentMapper, VectorStore vectorStore) {
        this.ragDocumentMapper = ragDocumentMapper;
        this.vectorStore = vectorStore;
    }

    /**
     * 异步处理文档：读取 → 切分 → 给 chunk 设置 ID → 向量化 → 存 Milvus → 更新状态
     * <p>
     * chunk ID 格式：doc{documentId}_{index}
     * 为什么要自定义 ID：Spring AI 默认给每个 chunk 生成随机 UUID，
     * 和 MySQL 的 documentId 没有关联，删除文档时无法定位 Milvus 里的 chunk。
     * 用 doc{documentId}_{index} 后，删除时可以精确构造 ID 列表批量删除。
     */
    @Async
    public void processDocument(Long documentId, String filePath) {
        try {
            log.info("开始处理文档, documentId={}", documentId);

            // 1. Tika 读取文件内容（自动识别 PDF/Word/txt 格式）
            TikaDocumentReader reader = new TikaDocumentReader(new FileSystemResource(filePath));
            List<Document> documents = reader.get();

            // 2. 切分成 chunk
            TokenTextSplitter splitter = TokenTextSplitter.builder().build();
            List<Document> chunks = splitter.apply(documents);

            // 3. 给每个 chunk 设置自定义 ID，关联 documentId
            // 格式：doc{documentId}_{index}，删除文档时按这个规则构造 ID 列表
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

            // 4. 向量化并存入 Milvus（VectorStore 内部调 EmbeddingModel）
            vectorStore.add(namedChunks);

            // 5. 更新状态为成功
            ragDocumentMapper.updateStatus(documentId, "SUCCESS", namedChunks.size(), null);
            log.info("文档处理完成, documentId={}, chunks={}", documentId, namedChunks.size());

        } catch (Exception e) {
            log.error("文档处理失败, documentId={}", documentId, e);
            ragDocumentMapper.updateStatus(documentId, "FAILED", 0, e.getMessage());
        }
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

        // 3. 软删除 MySQL 记录
        ragDocumentMapper.deleteById(documentId);
        log.info("文档已删除, documentId={}", documentId);
    }
}
