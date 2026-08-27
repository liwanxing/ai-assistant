package com.liwx.aiassistant.agent.tool;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import com.liwx.aiassistant.rag.mapper.RagChunkMapper;
import com.liwx.aiassistant.rag.service.QueryRewriteService;
import com.liwx.aiassistant.rag.service.RerankService;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * RAG 知识库检索工具（混合检索 + 查询改写）
 *
 * 检索策略：查询改写（Multi-Query）→ 向量检索（语义相似）+ 关键词检索（精确匹配）
 *           → RRF 融合+粗筛 → Rerank 精排 → 窗口扩容+临近拼接（small-to-big）
 *           → 合并后复评（双路命中加成）+ 置信度门控
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
 * 为什么 RAG 是 @Tool 而不是 Advisor：
 *   这是 Agent，模型有意图识别——问"几点了"不需要检索知识库，只有问知识库相关问题时才该搜
 *   Advisor 是"每次都执行"，模型管不了；@Tool 是模型自己决定调不调
 *   纯 RAG 应用（固定每次都搜）才适合放 Advisor，Agent 场景该放 Tool
*/
@Slf4j
@Component
public class RagTool {

    /** 单路检索基础召回量：变体越多每路摊得越少，总候选量大致持平（避免改写反而引入噪声） */
    private static final int TOTAL_TOP_K = 10;

    /** RRF 平滑常数：60 是论文和业界的标准取值，越大则名次差异对分数的影响越平缓 */
    private static final int RRF_K = 60;

    /** 粗筛保留量：融合分 top-N 才送 Rerank——Rerank 按候选条数计费、时延线性增长，先砍量再精排 */
    private static final int RERANK_INPUT_TOP_K = 8;

    /** 窗口扩容半径：命中的 chunk 往前后各扩几段（small-to-big：小 chunk 保检索精度，扩出来保上下文完整） */
    private static final int WINDOW_RADIUS = 1;

    /**
     * 双路命中加成系数（CombMNZ 思想）：拼接后的 span 同时含向量命中和关键词（倒排）命中时，
     * 复评分 = maxRerank × 1.2——语义像 + 字面像双重佐证。启发式可调：调到 1 就是不加成
     */
    private static final double DUAL_PATH_BONUS = 1.2;

    /** chunk ID 格式 doc{documentId}_{index}：位置信息就编码在 ID 里，窗口扩容靠它定位相邻段 */
    private static final Pattern CHUNK_ID_PATTERN = Pattern.compile("^doc(\\d+)_(\\d+)$");

    private final VectorStore vectorStore;
    private final RerankService rerankService;
    private final RagChunkMapper ragChunkMapper;
    private final QueryRewriteService queryRewriteService;

    /** 置信度门控阈值：Rerank top1 分数低于它就拒答（见 searchKnowledge 里的门控注释） */
    private final double rerankMinScore;

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
                   QueryRewriteService queryRewriteService,
                   @Value("${rag.rerank.min-score:0.3}") double rerankMinScore) {
        this.vectorStore = vectorStore;
        this.rerankService = rerankService;
        this.ragChunkMapper = ragChunkMapper;
        this.queryRewriteService = queryRewriteService;
        this.rerankMinScore = rerankMinScore;
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

            // 3. RRF 融合 + 粗筛（一个方法，都在 Rerank 之前干完，见 fuseCandidates 注释）
            FusedCandidates fused = fuseCandidates(futures);
            if (fused.docs().isEmpty()) {
                return "未找到相关资料";
            }
            log.info("检索完成：{} 路查询并行（向量命中 {} + 关键词命中 {}），融合+粗筛后 {} 条候选送精排",
                    queries.size(), fused.vectorHit(), fused.keywordHit(), fused.docs().size());

            // 4. Rerank 精排：交叉编码器逐对比较，取最相关的 3 条
            //    注意用原查询而不是变体：Rerank 要对齐用户的真实意图，变体只是检索手段
            List<Document> reranked = rerankService.rerank(query, fused.docs(), 3);
            if (reranked.isEmpty()) {
                return "未找到相关资料";
            }

            // 5. 窗口扩容 + 临近拼接（small-to-big）：命中的 chunk 反查相邻段拼成完整上下文，
            //    防“答案正好卡在切分边界上”（见 expandChunkWindows 注释）
            List<PendingSpan> pendingSpans = expandChunkWindows(reranked);
            if (pendingSpans.isEmpty()) {
                return "未找到相关资料";
            }

            // 6. 合并后复评（CombMNZ 思想）：拼接后的 span 重新聚合打分（见 reScoreSpans 注释）
            List<ExpandedSpan> spans = reScoreSpans(pendingSpans);

            // 7. 置信度门控（confidence gate）：放在最后——到这一步才拿到模型真正要看的
            //    最终资料（拼接段 + 复评分），对它做拒答判断才合理。
            //    RAG 最大的幻觉来源不是模型乱编，而是“检索到一堆不相关资料还硬答”：
            //    top1 分数低于阈值 = 检索没命中，明确拒答比硬塞弱相关资料更可靠。
            //    注意：rerank 分数不是概率，只是相对值；阈值宁低勿高——设高会误杀该答的题。
            double topScore = spans.get(0).score();
            log.info("置信度门控：top1 复评分 {}（双路命中加成已计入），阈值 {}",
                    String.format("%.3f", topScore), rerankMinScore);
            if (topScore < rerankMinScore) {
                log.info("门控触发，拒答防幻觉（top1={} < 阈值 {}）", topScore, rerankMinScore);
                return "知识库中没有找到与该问题足够相关的资料，无法可靠回答。请换个问法，或确认该问题是否属于知识库的覆盖范围。";
            }

            // 8. 拼接成文本返回给模型
            StringBuilder result = new StringBuilder();
            for (int i = 0; i < spans.size(); i++) {
                result.append("【参考资料").append(i + 1).append("]\n")
                        .append(spans.get(i).text())
                        .append("\n\n");
            }

            log.info("RagTool 返回 {} 条参考资料（混合检索 + 窗口扩容）", spans.size());
            return result.toString();

        } catch (Exception e) {
            log.warn("RagTool 混合检索失败：{}", e.getMessage());
            return "知识库检索失败，请尝试直接回答";
        }
    }

    /**
     * RRF 融合 + 粗筛（都在 Rerank 之前，合成一步）：把多路检索结果合并成一份统一排序的候选，
     * 融合分 top-N 才有资格送精排
     *
     * RRF（Reciprocal Rank Fusion）打分公式：score(chunk) = Σ 各路 1/(k + rank)，rank 从 1 起
     *   - 只用排名不用原始分：向量的 cosine 分和 MySQL 全文分不可比，排名才是公共语言
     *   - 平滑常数 k=60（业界标准值）：压住“第 1 名 vs 第 2 名”的分差，避免单路霸榜——
     *     于是“4 路都检索到”的 chunk 天然比“只在 1 路排第 1”的高，多路共识 > 单路自信
     *   - 纯函数计算不碰大模型，毫秒级
     *
     * 对比以前的 putIfAbsent（先到优先）：原查询那路垄断去重，变体检到的高分无法反超
     *
     * 粗筛在同一处收尾：Rerank 按候选条数计费、时延随条数线性增长——融合完顺手砍到
     * top-RERANK_INPUT_TOP_K 再送精排（候选少时省不了几毫秒，TOTAL_TOP_K 调大后这里是成本闸门）
     */
    private FusedCandidates fuseCandidates(List<CompletableFuture<RetrievalResult>> futures) {
        Map<String, Double> rrfScores = new HashMap<>();
        Map<String, Document> docsById = new LinkedHashMap<>();
        int vectorHit = 0;
        int keywordHit = 0;
        for (CompletableFuture<RetrievalResult> f : futures) {
            RetrievalResult r = f.join();
            vectorHit += r.vectorHit();
            keywordHit += r.keywordHit();
            List<Document> pathDocs = r.docs();
            for (int i = 0; i < pathDocs.size(); i++) {
                Document doc = pathDocs.get(i);
                rrfScores.merge(doc.getId(), 1.0 / (RRF_K + i + 1), Double::sum);
                docsById.putIfAbsent(doc.getId(), doc);
            }
        }
        // 按融合分降序，取 top-N 送精排；分数写进 metadata（rrf_score），日志校准用（习惯同 rerank_score）
        List<Document> sorted = new ArrayList<>(docsById.values());
        sorted.sort((a, b) -> Double.compare(rrfScores.get(b.getId()), rrfScores.get(a.getId())));
        if (sorted.size() > RERANK_INPUT_TOP_K) {
            log.info("粗筛：{} 条融合候选 → top-{} 送精排", sorted.size(), RERANK_INPUT_TOP_K);
            sorted = sorted.subList(0, RERANK_INPUT_TOP_K);
        }
        for (Document doc : sorted) {
            doc.getMetadata().put("rrf_score", rrfScores.get(doc.getId()));
        }
        return new FusedCandidates(sorted, vectorHit, keywordHit);
    }

    /**
     * 窗口扩容 + 临近拼接（small-to-big）：只管几何——扩容、取邻居、按位置拼成完整片段
     *
     * 解决的问题：切分边界常把一句话/一个答案切成两半——问“报销标准”，
     * 答案正好骑在 chunk 3 和 chunk 4 的边界上，模型只能看到半截
     *
     * 做法（利用 chunk ID 自带的位置信息 doc{documentId}_{index}）：
     *   1. 解析 top-3 命中的 (文档号， 序号)
     *   2. 每个命中向前后各扩 WINDOW_RADIUS 段，元组 IN 一条 SQL 批量取回（不逐段查，防 N+1）
     *   3. 同文档里窗口相邻/重叠的命中拼成一条（临近窗口拼接），段内按序号升序排原文；
     *      每段带上被吸收的命中名单（PendingSpan.hits），证据留给复评方法算分
     *
     * 与复评分家的原因：这里是位置几何问题（写对就不动），打分是证据强度问题
     * （系数要调、公式可能换）——变化原因不同，各自独立演化
     *
     * 代价控制：只对精排后的 top-3 扩容、半径 1——最多多取 6 段，token 膨胀可控
     * 容错：ID 解析不出来（理论不会发生）就把该 chunk 自成一段，不伤主链路
     */
    private List<PendingSpan> expandChunkWindows(List<Document> topDocs) {
        // 命中位置按 rerank 顺序保序存放；targets 收集所有要取原文的位置（含邻居）
        Map<ChunkPos, Document> hits = new LinkedHashMap<>();
        Set<ChunkPos> targets = new HashSet<>();
        for (Document doc : topDocs) {
            ChunkPos pos = parseChunkId(doc.getId());
            if (pos == null) {
                continue;
            }
            hits.put(pos, doc);
            for (int offset = -WINDOW_RADIUS; offset <= WINDOW_RADIUS; offset++) {
                int idx = pos.index() + offset;
                if (idx >= 0) {
                    targets.add(new ChunkPos(pos.documentId(), idx));
                }
            }
        }
        if (hits.isEmpty()) {
            // ID 解析不出位置：每个命中自成一段（吸收名单就是它自己，分数交给复评统一算）
            return topDocs.stream().map(doc -> new PendingSpan(doc.getText(), List.of(doc))).toList();
        }
    
        // 一条元组 IN 取回全部窗口段原文：位置 → 内容
        Map<ChunkPos, String> contentByPos = new HashMap<>();
        for (Map<String, Object> row : ragChunkMapper.selectByPositions(toPositionMaps(targets))) {
            contentByPos.put(new ChunkPos(((Number) row.get("document_id")).longValue(),
                            ((Number) row.get("chunk_index")).intValue()),
                    (String) row.get("content"));
        }
    
        // 临近窗口拼接：同文档里窗口相邻/重叠的命中并成一段（如命中 2 和 3 → 拼成 1~4 一整段），
        // span 的先后顺序由它在 top-3 里的排名决定（LinkedHashMap 的遍历顺序即 rerank 顺序）
        List<PendingSpan> spans = new ArrayList<>();
        Set<ChunkPos> consumed = new HashSet<>();
        for (ChunkPos hit : hits.keySet()) {
            if (!consumed.add(hit)) {
                continue; // 已被前面的 span 吸收
            }
            long docId = hit.documentId();
            int start = hit.index();
            int end = hit.index();
            // 两个命中的窗口相邻/重叠 ⟺ 序号距离 ≤ 2×半径；逐步吸收直到收敛（传递性合并）
            boolean grew = true;
            while (grew) {
                grew = false;
                for (ChunkPos other : hits.keySet()) {
                    if (consumed.contains(other) || other.documentId() != docId) {
                        continue;
                    }
                    if (other.index() >= start - 2 * WINDOW_RADIUS && other.index() <= end + 2 * WINDOW_RADIUS) {
                        start = Math.min(start, other.index());
                        end = Math.max(end, other.index());
                        consumed.add(other);
                        grew = true;
                    }
                }
            }
            // span 定稿：窗口内真实存在的段按序号升序拼接（越界的邻居查不到就跳过），
            // 窗口遍历范围 ⊇ 吸收区间，被吸收的命中在这里收齐名单
            StringBuilder text = new StringBuilder();
            List<Document> absorbed = new ArrayList<>();
            for (int idx = Math.max(0, start - WINDOW_RADIUS); idx <= end + WINDOW_RADIUS; idx++) {
                String content = contentByPos.get(new ChunkPos(docId, idx));
                if (content != null) {
                    text.append(content);
                }
                Document hitDoc = hits.get(new ChunkPos(docId, idx));
                if (hitDoc != null) {
                    absorbed.add(hitDoc);
                }
            }
            spans.add(new PendingSpan(text.toString(), absorbed));
        }
        log.info("窗口扩容+临近拼接：{} 条命中 → {} 段（含相邻段）", hits.size(), spans.size());
        return spans;
    }
    
    /**
     * 合并后复评（CombMNZ 思想）：对拼接好的 span 重新打分，只管证据强度
     *
     * 为什么拼接完还要再打一轮：拼接前的单个命中可能只被向量路命中，但合并后的
     * span 里可能同时含向量命中和关键词（倒排）命中——“语义像”+“字面像”双重佐证，
     * 比单路可信得多。落地为双路命中加成系数（启发式，可调）：
     *   score = maxRerank × (双路命中 ? DUAL_PATH_BONUS : 1.0)，封顶 1.0，门控用它拒答/放行
     *
     * max 取命中里的最高分：答案主体在哪个命中，span 的证据强度就由谁代表
     */
    private List<ExpandedSpan> reScoreSpans(List<PendingSpan> pending) {
        List<ExpandedSpan> scored = new ArrayList<>(pending.size());
        for (PendingSpan span : pending) {
            double maxRerank = 0;
            boolean vectorSourced = false;
            boolean keywordSourced = false;
            for (Document hit : span.hits()) {
                maxRerank = Math.max(maxRerank, rerankScoreOf(hit));
                vectorSourced |= Boolean.TRUE.equals(hit.getMetadata().get("src_vector"));
                keywordSourced |= Boolean.TRUE.equals(hit.getMetadata().get("src_keyword"));
            }
            boolean dualHit = vectorSourced && keywordSourced;
            double score = dualHit ? Math.min(maxRerank * DUAL_PATH_BONUS, 1.0) : maxRerank;
            scored.add(new ExpandedSpan(span.text(), score, dualHit));
        }
        log.info("合并后复评：{} 段，双路命中加成 {} 段（CombMNZ）",
                scored.size(), scored.stream().filter(ExpandedSpan::dualHit).count());
        return scored;
    }

    /** 命中的 rerank 分：Rerank 失败降级时没有分数，默认 1.0（放行，同“降级不拒答”语义） */
    private double rerankScoreOf(Document doc) {
        return doc.getMetadata().get("rerank_score") instanceof Number n ? n.doubleValue() : 1.0;
    }

    /** chunk ID → 位置：doc{documentId}_{index} 解析，格式不符返回 null（容错用） */
    private ChunkPos parseChunkId(String chunkId) {
        Matcher matcher = CHUNK_ID_PATTERN.matcher(chunkId);
        return matcher.matches()
                ? new ChunkPos(Long.parseLong(matcher.group(1)), Integer.parseInt(matcher.group(2)))
                : null;
    }

    /** Set<ChunkPos> → MyBatis foreach 好处理的 Map 列表（元组 IN 的入参） */
    private List<Map<String, Object>> toPositionMaps(Set<ChunkPos> positions) {
        List<Map<String, Object>> list = new ArrayList<>(positions.size());
        for (ChunkPos pos : positions) {
            Map<String, Object> m = new HashMap<>();
            m.put("documentId", pos.documentId());
            m.put("chunkIndex", pos.index());
            list.add(m);
        }
        return list;
    }

    /** RRF 融合结果：docs 已按融合分降序并粗筛到 top-N（分数在 metadata.rrf_score），命中数用于日志 */
    private record FusedCandidates(List<Document> docs, int vectorHit, int keywordHit) {}

    /** chunk 的位置坐标：文档号 + 段序号（从 chunk ID 里解析出来的） */
    private record ChunkPos(long documentId, int index) {}

    /** 拼接的中间产物：text 是拼好的原文，hits 是被这个 span 吸收的命中（打分的证据） */
    private record PendingSpan(String text, List<Document> hits) {}

    /** 复评后的最终片段：text 是给模型的原文，score 是含双路命中加成的复评分（门控用），dualHit 供日志展示 */
    private record ExpandedSpan(String text, double score, boolean dualHit) {}

    /**
     * 单路检索（线程池的任务单元）：一个查询变体的 向量 + 关键词 双路检索，路内合并去重
     * 独立成方法的原因：这是提交给线程池的最小任务单元，失败粒度控制在单路
     */
    private RetrievalResult searchSingleQuery(String query, int topK) {
        // 路内也用 LinkedHashMap：向量结果优先（带 score/metadata 完整字段），关键词只补缺
        Map<String, Document> perQuery = new LinkedHashMap<>();

        // 向量检索（Milvus）：语义相似；给命中打上来源标记，合并后复评双路命中用
        List<Document> vectorResults = vectorStore.similaritySearch(SearchRequest.builder()
                .query(query)
                .topK(topK)
                .build());
        for (Document doc : vectorResults) {
            doc.getMetadata().put("src_vector", true);
            perQuery.put(doc.getId(), doc);
        }

        // 关键词检索（MySQL FULLTEXT）：精确匹配，只补向量没命中的 chunk
        List<Map<String, Object>> keywordResults = ragChunkMapper.searchByKeyword(query, topK);
        for (Map<String, Object> row : keywordResults) {
            String chunkId = (String) row.get("chunk_id");
            Document existing = perQuery.get(chunkId);
            if (existing != null) {
                // 同一 chunk 两路都命中：补标记不打对象（向量结果字段更全，保留它）
                existing.getMetadata().put("src_keyword", true);
            } else {
                perQuery.put(chunkId, Document.builder()
                        .id(chunkId)
                        .text((String) row.get("content"))
                        .metadata("src_keyword", true)
                        .build());
            }
        }

        return new RetrievalResult(vectorResults.size(), keywordResults.size(), List.copyOf(perQuery.values()));
    }

    /** 单路检索结果：docs 已按「向量优先、关键词补充」顺序去重 */
    private record RetrievalResult(int vectorHit, int keywordHit, List<Document> docs) {}
}
