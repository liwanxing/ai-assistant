package com.liwx.learning.agent.service;

import cn.dev33.satoken.stp.StpInterface;
import com.liwx.learning.agent.tool.CircuitBreakerToolCallback;
import com.liwx.learning.agent.tool.RagTool;
import com.liwx.learning.agent.tool.ResearchTool;
import com.liwx.learning.agent.tool.TimeTool;
import com.liwx.learning.agent.tool.ToolPermission;
import com.liwx.learning.agent.tool.UserQueryTool;
import com.liwx.learning.agent.tool.WeatherTool;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.document.Document;
import org.springframework.ai.support.ToolCallbacks;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 工具注册与筛选中心（三层筛选：常驻 + 权限 + 向量预筛）
 *
 * 解决的问题：工具全量注册时，每个工具的 name + description + 参数 Schema 都随请求发给模型——
 * 工具一多，token 固定开销线性膨胀，且候选越多模型选得越不准（误选/犹豫/绕开工具硬答）。
 *
 * 筛选分三层，逐层收敛：
 *   1. 常驻工具：TimeTool 这类高频低成本万金油工具，不走检索永远注册
 *      ——向量召回靠语义相似，"现在几点"和"时间工具描述"未必对得上，万金油兜底
 *   2. 权限过滤：@ToolPermission 标注的工具（如查用户信息），无权限直接排除出候选池
 *      ——权限是安全约束不是相关性问题，不能交给向量相似度决定
 *   3. 向量预筛：用户 query 与工具描述算相似度，top-K 召回（RAG of tools）
 *      ——向量负责召回（全量→K 个），function calling 负责精选（K 个→调哪个），
 *        预筛成本仅一次 embedding（毫秒级），比外挂一层 LLM 意图识别便宜几个量级
 *
 * 索引与本体分离（存用分离，与用户记忆同一模式）：
 *   Milvus 里只存"目录索引"（工具名 + 描述 + 权限码），工具实现还是 Spring 容器里的 Bean；
 *   两边通过工具名关联。加新工具 = 写工具类 + 登记进 RETRIEVABLE_TOOLS，启动自动重建索引。
 *
 * MCP 远程工具同样收编：MCP_ENABLED=true 时远程 callbacks 包熔断后进同一套索引与筛选
 *（到本地也是 ToolCallback，无需适配层），AiConfig 不再做 builder 级全量注册。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ToolRegistryService {

    /** 向量索引的 metadata 类型标记：与文档向量（默认）、用户记忆（user_memory）共用一个 collection，靠 type 隔离 */
    private static final String INDEX_TYPE = "tool_index";

    /** 向量检索每次召回的工具数：K 取小值，宁缺勿滥——漏召回有常驻工具兜底，多召回则每个都占 token */
    private static final int TOP_K = 3;

    /**
     * 常驻工具：不参与检索、永远注册
     * 筛选是"大概率不用就不带"，但时间这类万金油工具使用频率高、描述短（几十 token），
     * 走检索省的那点 token 抵不上漏召回的代价
     */
    private static final List<Class<?>> ALWAYS_ON_TOOLS = List.of(TimeTool.class);

    /** 可检索工具池：参与向量预筛的工具（新工具写完类在这里登记一行，索引和筛选全自动） */
    private static final List<Class<?>> RETRIEVABLE_TOOLS = List.of(
            RagTool.class, WeatherTool.class, UserQueryTool.class, ResearchTool.class);

    private final VectorStore vectorStore;
    private final StpInterface stpInterface;
    private final ApplicationContext applicationContext;
    /** MCP 远程工具（可选依赖）：MCP_ENABLED=true 时容器里才有 ToolCallbackProvider Bean，ObjectProvider 软引用不强依赖 */
    private final ObjectProvider<ToolCallbackProvider> mcpToolCallbackProvider;
    private final CircuitBreakerRegistry circuitBreakerRegistry;

    /** 工具名 -> callback：方法级索引（一个工具类可含多个 @Tool 方法，各自独立召回） */
    private final Map<String, ToolCallback> callbackIndex = new LinkedHashMap<>();

    /** 工具名 -> 所需权限码（无标注存空串）：降级路径下做内存权限过滤用 */
    private final Map<String, String> permissionIndex = new HashMap<>();

    /** 常驻工具的 callbacks，启动时构建后不变 */
    private final List<ToolCallback> alwaysOnCallbacks = new ArrayList<>();

    /**
     * 启动时构建本地索引 + 全量重建向量索引
     *
     * 为什么用 ApplicationReadyEvent 而不是 @PostConstruct：
     *   这里要调 embedding API 向量化工具描述（网络调用），放在容器就绪后执行，
     *   不阻塞 Bean 初始化；失败只影响向量检索（自动降级为全量注册），不影响启动
     */
    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        // 1. 常驻工具：生成 callback 直接持有，不建向量索引（不参与检索）
        for (Class<?> toolClass : ALWAYS_ON_TOOLS) {
            for (ToolCallback cb : ToolCallbacks.from(applicationContext.getBean(toolClass))) {
                alwaysOnCallbacks.add(cb);
            }
        }
        log.info("常驻工具注册 {} 个（不走检索）", alwaysOnCallbacks.size());

        // 2. 可检索工具：反射生成方法级 callback，构建本地索引 + 向量索引文档
        List<Document> indexDocs = new ArrayList<>();
        for (Class<?> toolClass : RETRIEVABLE_TOOLS) {
            String permission = resolvePermission(toolClass);
            for (ToolCallback cb : ToolCallbacks.from(applicationContext.getBean(toolClass))) {
                String toolName = cb.getToolDefinition().name();
                callbackIndex.put(toolName, cb);
                permissionIndex.put(toolName, permission);
                indexDocs.add(toIndexDocument(toolName, cb, permission));
            }
        }
        log.info("可检索工具索引 {} 个（方法级）：{}", callbackIndex.size(), callbackIndex.keySet());

        // 3. MCP 远程工具（可选）：MCP_ENABLED=true 时才有 Provider Bean
        //    到了本地也是 ToolCallback——包上熔断后进同一个索引、同一套筛选，
        //    不再做 builder 级全量注册（那会让远程工具每个请求都带，绕过三层筛选）
        ToolCallbackProvider mcpProvider = mcpToolCallbackProvider.getIfAvailable();
        if (mcpProvider == null) {
            log.info("MCP 客户端未启用（MCP_ENABLED=false），不接入远程工具");
        } else {
            ToolCallback[] mcpCallbacks = mcpProvider.getToolCallbacks();
            CircuitBreaker mcpCircuitBreaker = circuitBreakerRegistry.circuitBreaker("mcpCircuitBreaker");
            for (ToolCallback raw : mcpCallbacks) {
                ToolCallback wrapped = new CircuitBreakerToolCallback(raw, mcpCircuitBreaker);
                String toolName = wrapped.getToolDefinition().name();
                // MCP 工具本地无 @ToolPermission，默认公开（permission=''）；要收紧需在配置层统一赋权
                callbackIndex.put(toolName, wrapped);
                permissionIndex.put(toolName, "");
                indexDocs.add(toIndexDocument(toolName, wrapped, ""));
            }
            log.info("MCP 远程工具接入 {} 个（熔断包装 + 向量索引）", mcpCallbacks.length);
        }

        // 4. 全量重建向量索引：doc ID 固定为 tool-index-{工具名}，先删旧再写新（覆盖语义）
        //    工具描述改了 → 重启自动重新 embedding，不留脏数据
        try {
            vectorStore.delete(callbackIndex.keySet().stream()
                    .map(name -> docId(name)).collect(Collectors.toList()));
            vectorStore.add(indexDocs);
            log.info("工具向量索引重建完成，共 {} 条", indexDocs.size());
        } catch (Exception e) {
            // 降级：索引建不上（Milvus 挂了/embedding 超时），selectTools 检索时同样会失败并走全量降级
            log.warn("工具向量索引构建失败（检索将降级为权限内全量注册）：{}", e.getMessage());
        }
    }

    /**
     * 为本次请求挑选工具：常驻 ∪ 权限过滤后的向量 top-K
     *
     * 返回 List 而不是数组：避免 .tools() 重载解析歧义——
     * ToolCallback[] 会命中已弃用的 toolCallbacks(ToolCallback...) 重载，
     * List 作为单个 Object 传入 tools(Object...) 后由 2.0 的统一分发规则迭代注册
     *
     * @param query  用户问题（embedding 后与工具描述算相似度）
     * @param userId 当前用户（按其权限码过滤候选池；null 视为匿名，受控工具全部排除）
     */
    public List<ToolCallback> selectTools(String query, Long userId) {
        List<ToolCallback> selected = new ArrayList<>(alwaysOnCallbacks);
        try {
            // 权限过滤编进 filterExpression：权限条件和向量召回一次查询完成，
            // 先过滤再排序，不会出现"top-K 里一半被权限筛掉导致候选不足"的问题
            List<Document> hits = vectorStore.similaritySearch(SearchRequest.builder()
                    .query(query)
                    .topK(TOP_K)
                    .filterExpression(buildPermissionFilter(userId))
                    .build());
            for (Document hit : hits) {
                // 检索回来的 doc ID 还原成工具名，回本地索引取 callback；
                // 取不到 = 索引残留（工具已删但旧向量还在），跳过即可
                ToolCallback cb = callbackIndex.get(toolNameOf(hit.getId()));
                if (cb != null) {
                    selected.add(cb);
                }
            }
            log.info("工具筛选：常驻 {} + 检索命中 {}（query 相关工具：{}）",
                    alwaysOnCallbacks.size(), selected.size() - alwaysOnCallbacks.size(),
                    hits.stream().map(d -> toolNameOf(d.getId())).collect(Collectors.toList()));
        } catch (Exception e) {
            // 降级：Milvus 不可用时返回"常驻 + 权限内的全部可检索工具"
            // 筛选是优化不是功能，挂了最多多花点 token，不能因此让 Agent 没工具可用
            log.warn("工具向量检索失败，降级为权限内全量注册：{}", e.getMessage());
            selected.addAll(callbackIndex.entrySet().stream()
                    .filter(e1 -> hasPermission(userId, permissionIndex.get(e1.getKey())))
                    .map(Map.Entry::getValue)
                    .toList());
        }
        return selected;
    }

    /**
     * 权限过滤表达式：type 限定工具索引 + 权限条件
     *   匿名 / 无任何权限 → 只召回公开工具（permission == ''）
     *   拥有权限的用户      → 公开工具 + 权限码命中的受控工具
     *
     * 语义基准是"权限码"不是"登录"：登录只是拿到 userId 的前提（入场券），
     * 已登录但没有 user:list 的用户，在这里和匿名一样只见公开工具——
     * 与接口层 @SaCheckPermission("user:list") 对同一用户的判定结果一致，
     * 接口层拦住的，工具层同样拦住，模型绕不过去
     * 只用 == / || / && 语法：与用户记忆检索的 filterExpression 同一语法族，行为已验证
     */
    private String buildPermissionFilter(Long userId) {
        String base = "type == '" + INDEX_TYPE + "'";
        if (userId == null) {
            return base + " && permission == ''";
        }
        List<String> perms = stpInterface.getPermissionList(userId, "login");
        if (perms == null || perms.isEmpty()) {
            return base + " && permission == ''";
        }
        String permCondition = perms.stream()
                .map(p -> "permission == '" + p + "'")
                .collect(Collectors.joining(" || "));
        return base + " && (permission == '' || " + permCondition + ")";
    }

    /** 降级路径的内存权限判断：与 buildPermissionFilter 同一套规则，只是过滤时机从 Milvus 换成了 Java */
    private boolean hasPermission(Long userId, String requiredPermission) {
        if (requiredPermission == null || requiredPermission.isEmpty()) {
            return true;
        }
        if (userId == null) {
            return false;
        }
        List<String> perms = stpInterface.getPermissionList(userId, "login");
        return perms != null && perms.contains(requiredPermission);
    }

    /** 读工具类上的 @ToolPermission（没标注 = 公开工具，权限码存空串保证 metadata 字段恒存在） */
    private String resolvePermission(Class<?> toolClass) {
        ToolPermission annotation = toolClass.getAnnotation(ToolPermission.class);
        return annotation != null ? annotation.value() : "";
    }

    /** 工具描述 → 向量索引文档：text 是检索语料，metadata 携带权限码参与标量过滤 */
    private Document toIndexDocument(String toolName, ToolCallback callback, String permission) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("type", INDEX_TYPE);
        metadata.put("permission", permission);
        // 检索语料 = 工具名 + 描述：名字放前面是给向量的一份额外语义锚点（如 "searchKnowledge" 本身就含语义）
        String text = toolName + "：" + callback.getToolDefinition().description();
        return Document.builder()
                .id(docId(toolName))
                .text(text)
                .metadata(metadata)
                .build();
    }

    private String docId(String toolName) {
        return "tool-index-" + toolName;
    }

    private String toolNameOf(String docId) {
        return docId.substring("tool-index-".length());
    }
}
