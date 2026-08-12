package com.liwx.learning.config;

import com.liwx.learning.agent.tool.RagTool;
import com.liwx.learning.agent.tool.TimeTool;
import com.liwx.learning.rag.advisor.ConversationSummaryAdvisor;
import com.liwx.learning.rag.advisor.UserMemoryAdvisor;
import com.liwx.learning.rag.mapper.ConversationSummaryMapper;
import com.liwx.learning.rag.service.UserMemoryService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AI 统一配置类
 * 统一管理 ChatClient 和 ChatMemory 的构建，Controller 只管用
 *
 * 演进说明：
 *   第一版（已废弃）：ChatClient 绑定了 RagAdvisor，每个请求固定走向量检索
 *   现在：ChatClient 不带 RAG，RAG 逻辑封装在 RagTool 里，通过 Function Calling 让模型自主决定是否调用
 */
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
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(30)  // 30 = 摘要缓冲区：ConversationSummaryAdvisor 只取最近 20 条，多出 10 条用来压缩
                .build();
    }

    /**
     * 构建 ChatClient：注册多轮对话记忆 + 长期记忆 + 摘要压缩 + 日志
     * RAG 不在这里做——RAG 逻辑在 RagTool 里，通过 .tools(ragTool) 注册给模型，模型自己决定是否调用
     *
     * 关键：必须注入 Spring AI 自动配置的 ChatClient.Builder Bean，而不是用 ChatClient.builder(chatModel) 静态方法。
     * 因为自动配置的 Builder 里包含了 ToolCallingAdvisor（工具调用循环的核心组件），
     * 静态方法创建的 Builder 不含此 Advisor，会导致 .tools() 注册的工具不会被发送给模型。
     */
    @Bean
    public ChatClient chatClient(ChatClient.Builder chatClientBuilder, ChatMemory chatMemory,
                                 ChatModel chatModel, ConversationSummaryMapper summaryMapper,
                                 UserMemoryService userMemoryService,
                                 RagTool ragTool, TimeTool timeTool) {
        return chatClientBuilder
                .defaultTools(ragTool, timeTool)  // 在 Builder 层面注册工具，确保 ToolCallingAdvisor 能序列化到 HTTP 请求
                .defaultAdvisors(
                        new UserMemoryAdvisor(userMemoryService),     // 长期记忆：注入用户偏好 + 异步提取
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        new ConversationSummaryAdvisor(chatModel, summaryMapper, 20),  // 消息超过20轮触发摘要压缩
                        new SimpleLoggerAdvisor()  // 官方日志 Advisor：能打印 tools 定义、tool_calls、finish_reason 等完整信息
                )
                .build();
    }
}
