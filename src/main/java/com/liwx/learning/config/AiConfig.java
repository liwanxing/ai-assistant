package com.liwx.learning.config;

import com.liwx.learning.rag.advisor.ConversationSummaryAdvisor;
import com.liwx.learning.rag.advisor.SemanticCacheAdvisor;
import com.liwx.learning.rag.advisor.TokenUsageAdvisor;
import com.liwx.learning.rag.advisor.UserMemoryAdvisor;
import com.liwx.learning.rag.service.ConversationSummaryService;
import com.liwx.learning.rag.service.SemanticCacheStore;
import com.liwx.learning.rag.service.UserMemoryService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import com.liwx.learning.rag.advisor.LangfuseAdvisor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import lombok.extern.slf4j.Slf4j;

/**
 * Spring AI 统一配置类
 * 统一管理 ChatClient 和 ChatMemory 的构建，Controller 只管用
 *
 * 演进说明：
 *   第一版（已废弃）：ChatClient 绑定了 RagAdvisor，每个请求固定走向量检索
 *   现在：ChatClient 不带 RAG，RAG 逻辑封装在 RagTool 里，通过 Function Calling 让模型自主决定是否调用
 */
@Slf4j
@Configuration
public class AiConfig {

    /**
     * 多轮对话记忆：对话历史存 MySQL，滑动窗口最多保留 20 条消息
     *
     * JdbcChatMemoryRepository 由 Spring AI 自动配置创建：检测到 pom 里的 jdbc starter +已有的 DataSource，
     * 自动连上 MySQL，启动时建表 SPRING_AI_CHAT_MEMORY（存 conversation_id、message 等字段）
     */
    @Bean
    public ChatMemory chatMemory(JdbcChatMemoryRepository repository) {
        ChatMemory delegate = MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(30)  // 30 = 摘要缓冲区：ConversationSummaryAdvisor 只取最近 20 条，多出 10 条用来压缩
                .build();
        // 包装一层：多模态消息（含图片）存之前剥离 base64 媒体数据，只保留文本，避免数据库膨胀
        return new MediaStrippingChatMemory(delegate);
    }

    /**
     * 构建 ChatClient：注册多轮对话记忆 + 长期记忆 + 摘要压缩 + 日志
     * RAG 不在这里做——RAG 逻辑在 RagTool 里，通过 .tools(ragTool) 注册给模型，模型自己决定是否调用
     *
     * 工具不在 Builder 层注册，而是在每次请求时通过 .tools() 注册（官方推荐做法）。
     */
    @Bean
    public ChatClient chatClient(ChatClient.Builder chatClientBuilder, ChatMemory chatMemory,
                                 ConversationSummaryService summaryService,
                                 UserMemoryService userMemoryService,
                                 SemanticCacheStore semanticCacheStore,
                                 ObjectProvider<ToolCallbackProvider> mcpToolCallbackProvider,
                                 @Autowired(required = false) RestClient langfuseRestClient) {
        // MCP 工具是可选能力：默认 MCP_ENABLED=false 时不注册远程工具（不强依赖 graph-learning-java 项目），
        // 联调时设环境变量 MCP_ENABLED=true，Spring AI 自动创建 McpToolCallbackProvider 后这里才生效
        ToolCallbackProvider toolCallbackProvider = mcpToolCallbackProvider.getIfAvailable();
        ChatClient.Builder builder = chatClientBuilder
                .defaultAdvisors(
                        new TokenUsageAdvisor(),                       // Token 监控：记录每次调用的 token 消耗
                        new UserMemoryAdvisor(userMemoryService),     // 长期记忆：注入用户偏好 + 异步提取
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        new ConversationSummaryAdvisor(summaryService),  // 摘要压缩：超过20轮触发（核心逻辑在 Service）
                        new SimpleLoggerAdvisor(),  // 官方日志 Advisor：能打印 tools 定义、tool_calls、finish_reason 等完整信息
                        // 语义缓存：命中直接返回缓存答案（0 token 毫秒级）。放最内层（order=1000）——
                        // 短路时只跳过 LLM 调用，外层的记忆读写/Token 监控照常执行；
                        // 命中时无 Usage 元数据 → TokenUsage 不打 token 行（账本不重复计账，
                        // 节省量 = 命中次数 × 同类问题首次调用的平均 token，是对比值非单次可读）
                        new SemanticCacheAdvisor(semanticCacheStore)
                );

        // Langfuse 可观测性：@ConditionalOnProperty 控制 Bean 是否创建
        // 没配 spring.ai.langfuse.enabled=true → Bean 不存在 → langfuseRestClient = null → 不注册
        // 配了 → Bean 存在 → 注册 LangfuseAdvisor，追踪每次 ChatClient 调用
        if (langfuseRestClient != null) {
            builder.defaultAdvisors(new LangfuseAdvisor(langfuseRestClient));
            log.info("Langfuse 可观测性已启用");
        }

        if (toolCallbackProvider == null) {
            log.info("MCP 客户端未启用（MCP_ENABLED=false），不注册远程工具");
        } else {
            // 打印 MCP 远程工具注册情况，启动时一眼看到连了哪些远程工具
            ToolCallback[] mcpCallbacks = toolCallbackProvider.getToolCallbacks();
            log.info("MCP 远程工具注册完成，共 {} 个：", mcpCallbacks.length);
            for (ToolCallback tc : mcpCallbacks) {
                log.info("  └─ {} : {}", tc.getToolDefinition().name(), tc.getToolDefinition().description());
            }
            // MCP 远程工具：defaultTools 是 Spring AI 2.0 替代 defaultToolCallbacks 的新 API
            builder = builder.defaultTools(toolCallbackProvider);
        }
        return builder.build();
    }
}
