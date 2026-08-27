package com.liwx.aiassistant.config;

import com.liwx.aiassistant.chat.advisor.ConversationSummaryAdvisor;
import com.liwx.aiassistant.chat.advisor.SemanticCacheAdvisor;
import com.liwx.aiassistant.chat.advisor.UserMemoryAdvisor;
import com.liwx.aiassistant.chat.advisor.core.MediaStrippingChatMemory;
import com.liwx.aiassistant.chat.advisor.core.ReadLimitChatMemory;
import com.liwx.aiassistant.chat.advisor.core.ConversationSummaryService;
import com.liwx.aiassistant.chat.advisor.core.SemanticCacheStore;
import com.liwx.aiassistant.chat.advisor.core.UserMemoryService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import com.liwx.aiassistant.chat.advisor.LangfuseAdvisor;
import org.springframework.beans.factory.annotation.Autowired;
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
     * 多轮对话记忆：对话历史存 MySQL（存用分离：这里只管「存」，模型「看多少」在下方 chatClient 里截断）
     *
     * JdbcChatMemoryRepository 由 Spring AI 自动配置创建：检测到 pom 里的 jdbc starter +已有的 DataSource，
     * 自动连上 MySQL，启动时建表 SPRING_AI_CHAT_MEMORY（存 conversation_id、message 等字段）
     *
     * maxMessages=500 是物理存储上限（≈250 轮对话）：超了才删最旧的，
     * 供 /messages 历史回看接口拿到完整档案；模型上下文只加载最近 30 条
     * （见 chatClient 里的 ReadLimitChatMemory，摘要 Advisor 再压成 摘要+20 条）
     */
    @Bean
    public ChatMemory chatMemory(JdbcChatMemoryRepository repository) {
        ChatMemory delegate = MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(500)  // 物理存储上限 ≈250 轮；模型上下文窗口与它解耦（ReadLimitChatMemory 截 30）
                .build();
        // 包装一层：多模态消息（含图片）存之前剥离 base64 媒体数据，只保留文本，避免数据库膨胀
        // （此 Bean 在下方 chatClient 里还会被 ReadLimitChatMemory 再包一层，两层嵌套而非替换：本层管写入剥图，那层管读取截断）
        return new MediaStrippingChatMemory(delegate);
    }

    /**
     * 构建 ChatClient：注册多轮对话记忆 + 长期记忆 + 摘要压缩 + 日志
     * RAG 不在这里做——RAG 逻辑在 RagTool 里，通过 .tools(ragTool) 注册给模型，模型自己决定是否调用
     *
     * 工具不在 Builder 层注册，而是在每次请求时通过 .tools() 注册（官方推荐做法）。
     * 本地工具和 MCP 远程工具统一走 ToolRegistryService 三层筛选（常驻 + 权限 + 向量预筛）。
     */
    @Bean
    public ChatClient chatClient(ChatClient.Builder chatClientBuilder, ChatMemory chatMemory,
                                 ConversationSummaryService summaryService,
                                 UserMemoryService userMemoryService,
                                SemanticCacheStore semanticCacheStore,
                                @Autowired(required = false) RestClient langfuseRestClient) {
        ChatClient.Builder builder = chatClientBuilder
                .defaultAdvisors(
                        new UserMemoryAdvisor(userMemoryService),     // 长期记忆：注入用户偏好 + 异步提取
                        MessageChatMemoryAdvisor.builder(new ReadLimitChatMemory(chatMemory, 30)).build(),
                        new ConversationSummaryAdvisor(summaryService),  // 摘要压缩：超过20轮触发（核心逻辑在 Service）
                        new SimpleLoggerAdvisor(),  // 官方日志 Advisor：能打印 tools 定义、tool_calls、finish_reason 等完整信息
                        new SemanticCacheAdvisor(semanticCacheStore)// 语义缓存：命中直接返回缓存答案（0 token 毫秒级）。
                );

        // Langfuse 可观测性：@ConditionalOnProperty 控制 Bean 是否创建
        if (langfuseRestClient != null) {
            builder.defaultAdvisors(new LangfuseAdvisor(langfuseRestClient));
            log.info("Langfuse 可观测性已启用");
        }

        return builder.build();
    }
}
