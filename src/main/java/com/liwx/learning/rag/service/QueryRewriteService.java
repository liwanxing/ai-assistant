package com.liwx.learning.rag.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * 查询改写服务（Query Rewriting / Multi-Query）
 *
 * 解决的问题：用户查询经常是口语化、模糊的（"报销的东西在哪点"），
 * 直接拿去检索会漏召回——向量检索对"意思接近但措辞不同"的内容命中率不稳定，
 * 关键词检索更依赖字面匹配。改写成多个规范变体后分别检索、合并候选，
 * 相当于把"单次掷骰子"变成"多次采样"，直接提升召回率。
 *
 * 链路位置：RagTool 检索前的预处理步骤，对内（Function Calling）和对外（MCP）共用
 *
 * 设计决策：
 *   1. 原查询始终保留在变体列表里——改写可能跑偏，原查询兜底防召回反而下降
 *   2. 用轻量模型（qwen-flash）而非主模型（qwen-plus）——改写是小任务，
 *      用大模型纯属浪费 token 和延迟，每次检索多花的成本控制在毫秒级+分厘级
 *   3. 任何失败（超时/解析错误/模型不可用）都降级为"只用原查询"——
 *      改写是增强手段，不能让它阻塞检索主链路
 *   4. 构造注入原始 ChatClient.Builder 而不是生产 ChatClient Bean——
 *      改写是无状态独立任务，不需要记忆/Token监控/UserMemory 那套 Advisor
 */
@Slf4j
@Service
public class QueryRewriteService {

    /**
     * 改写结果的结构化载体（Structured Output）
     * LLM 按 BeanOutputConverter 生成的 JSON Schema 输出 {"queries": ["...", "...", "..."]}
     */
    public record QueryVariants(List<String> queries) {}

    private final ChatClient rewriteClient;
    private final String rewritePrompt;
    private final boolean enabled;
    private final String model;

    public QueryRewriteService(ChatClient.Builder chatClientBuilder,
                               @Value("classpath:prompts/query-rewrite.st") Resource promptResource,
                               @Value("${rag.query-rewrite.enabled:true}") boolean enabled,
                               @Value("${rag.query-rewrite.model:qwen-flash}") String model) throws IOException {
        // 原始 Builder 构建：不继承生产 ChatClient 的默认 Advisor（记忆等对改写无意义且污染对话历史）
        // options 覆盖模型：改写走轻量模型，与主对话模型解耦（见 expand 里的 .options）
        this.rewriteClient = chatClientBuilder.build();
        this.rewritePrompt = promptResource.getContentAsString(StandardCharsets.UTF_8);
        this.enabled = enabled;
        this.model = model;
    }

    /**
     * 展开查询：原查询 + LLM 生成的改写变体
     *
     * @return 去重后的查询列表；改写关闭或失败时返回只含原查询的单元素列表（调用方无感降级）
     */
    public List<String> expand(String query) {
        if (!enabled) {
            return List.of(query);
        }
        try {
            QueryVariants variants = rewriteClient.prompt()
                    .system(rewritePrompt)
                    .user(query)
                    // Spring AI 2.0 写法：options() 直接收 Builder（不带 .build()），框架自动构建
                    .options(OpenAiChatOptions.builder().model(model))
                    .call()
                    .entity(QueryVariants.class);

            // 洗数据：原查询永远排第一（Rerank 阶段用它对齐真实意图），变体去空、去重
            LinkedHashSet<String> unique = new LinkedHashSet<>();
            unique.add(query);
            if (variants != null && variants.queries() != null) {
                variants.queries().stream()
                        .filter(q -> q != null && !q.isBlank())
                        .forEach(unique::add);
            }
            List<String> result = new ArrayList<>(unique);
            log.info("查询改写 [{} 个]：{}", result.size(), result);
            return result;

        } catch (Exception e) {
            // 降级：改写失败不影响检索，只用原查询（行为与没加改写时完全一致）
            log.warn("查询改写失败，降级为原查询直接检索：{}", e.getMessage());
            return List.of(query);
        }
    }
}
