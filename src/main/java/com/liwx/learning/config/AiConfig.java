package com.liwx.learning.config;

import com.liwx.learning.rag.advisor.ConversationSummaryAdvisor;
import com.liwx.learning.rag.advisor.TokenUsageAdvisor;
import com.liwx.learning.rag.advisor.UserMemoryAdvisor;
import com.liwx.learning.rag.service.ConversationSummaryService;
import com.liwx.learning.rag.service.UserMemoryService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

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
                                 ToolCallbackProvider mcpToolCallbackProvider) {
        // 打印 MCP 远程工具注册情况，启动时一眼看到连了哪些远程工具
        ToolCallback[] mcpCallbacks = mcpToolCallbackProvider.getToolCallbacks();
        log.info("MCP 远程工具注册完成，共 {} 个：", mcpCallbacks.length);
        for (ToolCallback tc : mcpCallbacks) {
            log.info("  └─ {} : {}", tc.getToolDefinition().name(), tc.getToolDefinition().description());
        }

        return chatClientBuilder
                .defaultAdvisors(
                        new TokenUsageAdvisor(),                       // Token 监控：记录每次调用的 token 消耗
                        new UserMemoryAdvisor(userMemoryService),     // 长期记忆：注入用户偏好 + 异步提取
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        new ConversationSummaryAdvisor(summaryService),  // 摘要压缩：超过20轮触发（核心逻辑在 Service）
                        new SimpleLoggerAdvisor()  // 官方日志 Advisor：能打印 tools 定义、tool_calls、finish_reason 等完整信息
                )
                .defaultTools(mcpToolCallbackProvider)  // MCP 远程工具：defaultTools 是 Spring AI 2.0 替代 defaultToolCallbacks 的新 API
                .build();
    }
}
