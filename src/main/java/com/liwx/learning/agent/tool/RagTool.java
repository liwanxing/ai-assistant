package com.liwx.learning.agent.tool;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.liwx.learning.rag.mapper.RagChunkMapper;
import com.liwx.learning.rag.service.QueryRewriteService;
import com.liwx.learning.rag.service.RerankService;
import jakarta.annotation.PreDestroy;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * RAG 知识库检索工具（混合检索 + 查询改写）
 *
 * 检索策略：查询改写（Multi-Query）→ 向量检索（语义相似）+ 关键词检索（精确匹配）
 *           → 合并去重 → Rerank 重排
 *
 * 为什么需要混合检索：
 *   纯向量检索：擅长语义匹配（"怎么休假" 能匹配到 "请假流程"），但对专有名词、编号容易漏
 *   纯关键词检索：擅长精确匹配（搜 "ISO9001" 直接命中），但不理解同义词
 *   两者互补：向量管"意思接近"，关键词管"字面包含"，Rerank 负责统一排序
 *
 * 为什么需要查询改写：
 *   用户查询常是口语化的（"报销的东西在哪点"），单次检索漏召回；
 *   改写成多个规范变体分别检索合并，相当于多次采样提升召回（详见 QueryRewriteService）
 *
 * 为什么并行检索：
 *   变体间互不依赖，串行是白等；虽然本地检索毫秒级、并行收益有限（链路大头在 LLM 调用），
 *   但这里把 CompletableFuture.allOf 协调多任务、单路降级、按序合并的标准范式练扎实了
 *
 * 这个类把原来 RagAdvisor 里「每次都执行」的检索逻辑，封装成一个 @Tool
 * 区别：
 *   RagAdvisor（旧）：每个请求都走向量检索，模型管不了
 *   RagTool（新）：模型看到 @Tool 的 description 后自己决定是否调用
 */
@Slf4j
@Component
public class RagTool {

    /** 单路检索基础召回量：变体越多每路摊得越少，总候选量大致持平（避免改写反而引入噪声） */
    private static final int TOTAL_TOP_K = 10;

    private final VectorStore vectorStore;
    private final RerankService rerankService;
    private final RagChunkMapper ragChunkMapper;
    private final QueryRewriteService queryRewriteService;

    /**
     * 检索专用线程池：变体最多 4 路（原查询 + 3 个变体），固定 4 线程刚好一路一线程
     *
     * 参数逐个说明（面试常问）：
     *   core=max=4        固定大小：任务数确定且小，不需要弹性伸缩
     *   SynchronousQueue  零容量队列：有空闲线程直接接任务，没有就走拒绝策略——
     *                     检索任务毫秒级，排队等待反而比直接执行更慢，不值得堆队列
     *   CallerRunsPolicy 满载时由提交任务的线程（Tomcat 工作线程）自己执行：
     *                     天然背压——下游再慢也丢不了任务，压力回传给调用方而不是压垮线程池
     *   daemon 线程       不阻塞 JVM 退出；命名 rag-retrieval-N，线程 dump / 日志里一眼认出
     */
    private final ExecutorService retrievalPool;

    public RagTool(VectorStore vectorStore, RerankService rerankService, RagChunkMapper ragChunkMapper,
                   QueryRewriteService queryRewriteService) {
        this.vectorStore = vectorStore;
        this.rerankService = rerankService;
        this.ragChunkMapper = ragChunkMapper;
        this.queryRewriteService = queryRewriteService;
        ThreadPoolExecutor pool = new ThreadPoolExecutor(
                4, 4,
                60L, TimeUnit.SECONDS,
                new SynchronousQueue<>(),
                new ThreadFactoryBuilder()
                        .setNameFormat("rag-retrieval-%d")
                        .setDaemon(true)
                        .build(),
                new ThreadPoolExecutor.CallerRunsPolicy());
        // 空闲时连核心线程也回收：不用检索时不常驻 4 个线程
        pool.allowCoreThreadTimeOut(true);
        this.retrievalPool = pool;
    }

    /** Bean 销毁时关闭线程池，避免应用下线时泄漏线程 */
    @PreDestroy
    public void shutdownPool() {
        retrievalPool.shutdown();
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
            // 1. 查询改写（Multi-Query）：口语化查询 → 原查询 + 多个规范变体
            //    改写失败或开关关闭时内部自动降级为只含原查询的列表，这里无需关心
            List<String> queries = queryRewriteService.expand(query);

            // 每路检索 topK 摊薄：4 个查询每个取 5 条，总候选量与单查询取 10 大致持平
            int perQueryTopK = Math.max(3, TOTAL_TOP_K / queries.size());

            // 2. 多路并行检索：每个变体一个任务提交线程池（变体间互不依赖，串行是白等）
            //    每路内部包含 向量 + 关键词 两步，失败粒度 = 单路：某一路抖了只损失那一路的候选
            List<CompletableFuture<RetrievalResult>> futures = new ArrayList<>();
            for (String q : queries) {
                futures.add(CompletableFuture
                        .supplyAsync(() -> searchSingleQuery(q, perQueryTopK), retrievalPool)
                        // 单路降级为空列表：Milvus/MySQL 某一路挂了不拖全局，其余路照常参与合并
                        .exceptionally(e -> {
                            log.warn("单路检索失败，降级为空结果（查询：{}）：{}", q, e.getMessage());
                            return new RetrievalResult(0, 0, List.of());
                        }));
            }

            // allOf 只是协调器（不产生并行，并行在 supplyAsync 提交时就发生了）：
            // 等全部完成。每个 future 都已 exceptionally 兜底，这里的 join 不会抛异常
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            // 按提交顺序合并（原查询第一路 → 变体依次），putIfAbsent 保持先到优先；
            // futures 列表顺序 = queries 顺序，天然保持了"原查询优先"的隐式权重
            Map<String, Document> merged = new LinkedHashMap<>();
            int vectorHitCount = 0;
            int keywordHitCount = 0;
            for (CompletableFuture<RetrievalResult> f : futures) {
                RetrievalResult r = f.join();
                vectorHitCount += r.vectorHit();
                keywordHitCount += r.keywordHit();
                for (Document doc : r.docs()) {
                    merged.putIfAbsent(doc.getId(), doc);
                }
            }

            log.info("检索完成：{} 路查询并行（向量命中 {} + 关键词命中 {}）",
                    queries.size(), vectorHitCount, keywordHitCount);

            List<Document> allCandidates = new ArrayList<>(merged.values());
            if (allCandidates.isEmpty()) {
                return "未找到相关资料";
            }

            log.info("合并去重后 {} 条候选（向量 {} + 关键词新增 {}）",
                    allCandidates.size(), vectorHitCount, allCandidates.size() - vectorHitCount);

            // 3. Rerank 重排序：从全部候选中取最相关的 3 条
            //    注意用原查询而不是变体：Rerank 要对齐用户的真实意图，变体只是检索手段
            List<Document> reranked = rerankService.rerank(query, allCandidates, 3);

            // 4. 拼接成文本返回给模型
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

    /**
     * 单路检索（线程池的任务单元）：一个查询变体的 向量 + 关键词 双路检索，路内合并去重
     * 独立成方法的原因：这是提交给线程池的最小任务单元，失败粒度控制在单路
     */
    private RetrievalResult searchSingleQuery(String query, int topK) {
        // 路内也用 LinkedHashMap：向量结果优先（带 score/metadata 完整字段），关键词只补缺
        Map<String, Document> perQuery = new LinkedHashMap<>();

        // 向量检索（Milvus）：语义相似
        List<Document> vectorResults = vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(topK)
                .build());
        for (Document doc : vectorResults) {
            perQuery.put(doc.getId(), doc);
        }

        // 关键词检索（MySQL FULLTEXT）：精确匹配，只补向量没命中的 chunk
        List<Map<String, Object>> keywordResults = ragChunkMapper.searchByKeyword(query, topK);
        for (Map<String, Object> row : keywordResults) {
            String chunkId = (String) row.get("chunk_id");
            perQuery.putIfAbsent(chunkId, Document.builder()
                    .id(chunkId)
                    .text((String) row.get("content"))
                    .build());
        }

        return new RetrievalResult(vectorResults.size(), keywordResults.size(), List.copyOf(perQuery.values()));
    }

    /** 单路检索结果：docs 已按「向量优先、关键词补充」顺序去重 */
    private record RetrievalResult(int vectorHit, int keywordHit, List<Document> docs) {}
}
