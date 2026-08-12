package com.liwx.learning.config;

import com.liwx.learning.rag.advisor.ChatLoggingAdvisor;
import com.liwx.learning.rag.advisor.ConversationSummaryAdvisor;
import com.liwx.learning.rag.advisor.UserMemoryAdvisor;
import com.liwx.learning.rag.mapper.ConversationSummaryMapper;
import com.liwx.learning.rag.service.UserMemoryService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring AI 统一配置类
 * 统一管理 ChatClient 和 ChatMemory 的构建，Controller 只管用
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
     * 构建 ChatClient，注册多轮对话记忆 Advisor
     * 以前需要手动 new OpenAiApi + OpenAiChatModel 传 model/key，现在 Builder 自动注入，build() 就行
     */
    @Bean
    public ChatClient chatClient(ChatClient.Builder builder, ChatMemory chatMemory,
                                 ChatModel chatModel, ConversationSummaryMapper summaryMapper,
                                 UserMemoryService userMemoryService) {
        return builder
                .defaultAdvisors(
                        new UserMemoryAdvisor(userMemoryService),  // 长期记忆：注入用户偏好 + 异步提取
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        new ConversationSummaryAdvisor(chatModel, summaryMapper, 5),  // TODO 测试值，正式改回 20
                        new ChatLoggingAdvisor()
                )
                .build();
    }
}
