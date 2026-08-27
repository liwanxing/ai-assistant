package com.liwx.aiassistant.chat.advisor.core;

import io.milvus.client.MilvusServiceClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.milvus.MilvusVectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 语义缓存存储（Semantic Cache）：把「问题 → 答案」对存进 Milvus 独立 collection
 *
 * 解决的问题："请假怎么请"和"请假流程是什么"是两次语义相同的问题，
 * 却各自独立调用一次 LLM（检索+生成 6~9 秒 + 全额 token）。
 * 相似度 > 阈值直接返回缓存答案：token 花费降为 0，响应从秒级到毫秒级。
 *
 * 为什么单独一个 collection（semantic_cache）而不是复用 rag_docs：
 *   两者数据形态完全不同——rag_docs 存"文档切片"（检索后给模型当资料），
 *   这里存"完整问答对"（命中后直接当答案返回），混在一起互相污染检索结果。
 *
 * 为什么本类是 @Component 而不是再注册一个 VectorStore Bean：
 *   Spring AI 自动配置创建主 vectorStore 的条件是 @ConditionalOnMissingBean(VectorStore)，
 *   多注册一个 VectorStore 类型的 Bean 会让自动配置退让 → 主 vectorStore 消失 → RagTool/RagService 全崩。
 *   本类内部持有 MilvusVectorStore 实例但不以 VectorStore 类型暴露，自动配置不受影响。
 */
@Slf4j
@Component
public class SemanticCacheStore {

    /** 缓存全量失效的过滤锚点：所有缓存条目都带 metadata cached=true，删它就等于清空 */
    private static final String INVALIDATE_FILTER = "cached == true";

    private final VectorStore cacheStore;
    private final boolean enabled;
    private final double similarityThreshold;
    private final long ttlMs;

    public SemanticCacheStore(VectorStore vectorStore, EmbeddingModel embeddingModel,
                              @Value("${rag.semantic-cache.enabled:true}") boolean enabled,
                              @Value("${rag.semantic-cache.similarity-threshold:0.95}") double similarityThreshold,
                              @Value("${rag.semantic-cache.ttl-days:7}") int ttlDays) {
        // 复用主 vectorStore 的底层 Milvus 连接（不新建连接，不额外消耗资源），
        // 维度不用手动设：MilvusVectorStore 自动从 embeddingModel.dimensions() 推断（1024）
        MilvusServiceClient milvusClient = (MilvusServiceClient) vectorStore.getNativeClient()
                .orElseThrow(() -> new IllegalStateException("主 VectorStore 未暴露 Milvus 原生客户端，无法创建语义缓存"));
        MilvusVectorStore store = MilvusVectorStore.builder(milvusClient, embeddingModel)
                .collectionName("semantic_cache")
                .initializeSchema(true)
                .build();
        // 手动 build 的实例不经 Spring 容器生命周期，initializeSchema 的建表逻辑挂在
        // InitializingBean.afterPropertiesSet() 里、容器不会替我们调——不显式调一次，
        // collection 就一直不存在（冷启动）：lookup/save 各自的 catch 能兕住，但
        // invalidate/purgeExpired 先跑就会触发 Milvus SDK 打出吓人的 "collection not found" ERROR
        try {
            store.afterPropertiesSet();
        } catch (Exception e) {
            // 建表失败不阻断启动：缓存是加速手段，各调用点的 catch 已兕底，下次重启再试
            log.warn("语义缓存 collection 初始化失败（缓存降级，重启后重试）：{}", e.getMessage());
        }
        this.cacheStore = store;
        this.enabled = enabled;
        this.similarityThreshold = similarityThreshold;
        this.ttlMs = ttlDays * 24L * 60 * 60 * 1000;
    }

    public boolean isEnabled() {
        return enabled;
    }

    /**
     * 查缓存：语义相似度超过阈值且未过 TTL 才算命中
     *
     * @return 命中返回缓存的答案文本；未命中/过期/出错返回 null（调用方无感降级直连 LLM）
     */
    public String lookup(String question) {
        try {
            List<Document> hits = cacheStore.similaritySearch(SearchRequest.builder()
                    .query(question)
                    .topK(1)
                    .similarityThreshold(similarityThreshold)
                    .build());
            if (hits.isEmpty()) {
                return null;
            }
            // TTL 检查：过期条目视同未命中（过期答案可能基于旧版知识库）
            Object createdAt = hits.get(0).getMetadata().get("created_at");
            if (createdAt instanceof Number n
                    && System.currentTimeMillis() - n.longValue() > ttlMs) {
                log.info("语义缓存命中但已过期，忽略（问题：{}）", question);
                return null;
            }
            return hits.get(0).getText();
        } catch (Exception e) {
            // 缓存是加速手段，任何故障都不能阻塞主链路
            log.warn("语义缓存查询失败，降级直连 LLM：{}", e.getMessage());
            return null;
        }
    }

    /**
     * 异步写缓存：写入要调 embedding API（几百毫秒），不能拖慢用户响应，
     * 用虚拟线程后台执行（任务轻量且 IO 密集，虚拟线程零成本）
     */
    public void saveAsync(String question, String answer) {
        if (answer == null || answer.isBlank()) {
            return;
        }
        Thread.ofVirtual().name("semantic-cache-writer").start(() -> {
            try {
                cacheStore.add(List.of(Document.builder()
                        .id("cache_" + UUID.randomUUID())
                        .text(answer)
                        .metadata(Map.of(
                                "question", question,          // 存原始问题便于排查（真正用于检索的是向量）
                                "created_at", System.currentTimeMillis(),
                                "cached", true))               // 全量失效的过滤锚点
                        .build()));
                log.debug("语义缓存已写入：{}", question);
            } catch (Exception e) {
                log.warn("语义缓存写入失败（不影响本次响应）：{}", e.getMessage());
            }
        });
    }

    /**
     * 全量失效：知识库文档上传/删除后调用。
     * 答案基于旧知识库的缓存全部作废——这是语义缓存最大的坑，
     * 不清缓存的话用户会一直拿到基于已删除文档的过期答案
     */
    public void invalidate() {
        try {
            cacheStore.delete(INVALIDATE_FILTER);
            log.info("语义缓存已全量失效（知识库文档发生变更）");
        } catch (Exception e) {
            if (isCollectionMissing(e)) {
                // collection 不存在 = 一条缓存都没写过 = 缓存本来就是空的，“清空”目标天然达成。
                // 冷启动场景（建表后还没写过任何缓存就上传/删除文档）会走到这
                log.info("语义缓存 collection 尚不存在（从未写入过缓存），无需失效");
                return;
            }
            log.warn("语义缓存失效失败（残留旧答案将等 TTL 过期后由定时清理删除）：{}", e.getMessage());
        }
    }

    /**
     * 物理清理过期条目：lookup 里的 TTL 检查只是“查询时忽略”过期条目，
     * 条目本身永远躺在 Milvus 里越积越多（每次写入都新增，永不删除）。
     * 定时清理任务每天调一次，按 created_at 过滤条件真正删除——
     * 和 invalidate 同一个 delete(filterExpression) 机制，只是过滤条件从“全部”变成“过期的”
     */
    public void purgeExpired() {
        try {
            // created_at 是写入时存的毫秒时间戳（见 saveAsync），早于 TTL 窗口起点的即为过期
            String filter = "created_at < " + (System.currentTimeMillis() - ttlMs);
            cacheStore.delete(filter);
            log.info("语义缓存过期条目已物理清理（created_at 早于 {} 天前）", ttlMs / 24L / 60 / 60 / 1000);
        } catch (Exception e) {
            if (isCollectionMissing(e)) {
                // 同 invalidate：collection 不存在说明缓存为空，没有可清理的
                log.info("语义缓存 collection 尚不存在（从未写入过缓存），无需清理");
                return;
            }
            // 清理失败不影响主流程，明天定时任务会重试
            log.warn("语义缓存物理清理失败（明天定时任务重试）：{}", e.getMessage());
        }
    }

    /**
     * 判断异常链里是否是"collection 不存在"：Spring AI 会把 Milvus 的 ServerException 包一层再抛，
     * 所以要沿 cause 链找；匹配文案是 Milvus 服务端的固定报错（collection not found）
     */
    private static boolean isCollectionMissing(Exception e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t.getMessage() != null && t.getMessage().contains("collection not found")) {
                return true;
            }
        }
        return false;
    }
}
