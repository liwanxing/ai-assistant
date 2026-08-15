package com.liwx.learning.agent.service;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
// import io.github.resilience4j.retry.annotation.Retry;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import lombok.extern.slf4j.Slf4j;

/**
 * AI 调用 Service 层
 *
 * 职责：封装所有 ChatClient 调用 + Resilience4j 弹性保护
 * Controller 只管 HTTP 协议（限流、会话管理、SSE 转换），不碰 LLM 调用细节
 *
 * 将来千问修了流式 bug，只需在这里把 .call() 改成 .stream()，Controller 完全不用动
 */
@Slf4j
@Service
public class AiClientService {

    private final ChatClient chatClient;
    private final String agentSystemPrompt;
    private final String agentImageSystemPrompt;

    public AiClientService(ChatClient chatClient,
                           org.springframework.beans.factory.ObjectProvider<Resource> agentSystemPromptProvider,
                           org.springframework.beans.factory.ObjectProvider<Resource> agentImageSystemPromptProvider) throws java.io.IOException {
        this.chatClient = chatClient;
        this.agentSystemPrompt = agentSystemPromptProvider.getIfAvailable()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        this.agentImageSystemPrompt = agentImageSystemPromptProvider.getIfAvailable()
                .getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
    }

    /**
     * Agent 对话（文本）
     * @CircuitBreaker：连续失败超阈值时自动熔断，返回降级提示，不傻等超时
     *
     * @Retry 注解说明（Resilience4j 自带，不是 Spring Retry）：
     *   Resilience4j 是全家桶，@CircuitBreaker（熔断）和 @Retry（重试）是它的两个独立注解。
     *   为什么不用 @Retry：
     *     1. 当前是同步调用（.call()），重试可以工作
     *     2. 将来千问修了 bug 换成流式（.stream()），流式 + 重试 = 内容重复（行业公认的难题）
     *     3. 流式场景下重试应该由前端"重新生成"按钮处理，不是后端自动重试
     *   所以这里预留了 @Retry 注解的位置，如果将来同步调用的场景需要重试，取消注释即可
     *   如果要换流式，删除 @Retry 注解，前端加"重新生成"按钮
     *
     * 配置在 application.yml 的 resilience4j.circuitbreaker.instances.llmCircuitBreaker
     */
    @CircuitBreaker(name = "llmCircuitBreaker", fallbackMethod = "fallback")
    // @Retry(name = "llmRetry", fallbackMethod = "fallback")  // 预留：同步调用可启用，流式调用不要用
    public String chat(String question, String sessionId, Long userId) {
        return chatClient.prompt()
                .system(agentSystemPrompt)
                .user(question)
                .tools(com.liwx.learning.agent.tool.RagTool.class,
                       com.liwx.learning.agent.tool.TimeTool.class,
                       com.liwx.learning.agent.tool.WeatherTool.class,
                       com.liwx.learning.agent.tool.UserQueryTool.class,
                       com.liwx.learning.agent.tool.ResearchTool.class)
                .advisors(a -> {
                    a.param(ChatMemory.CONVERSATION_ID, sessionId);
                    a.param(com.liwx.learning.rag.advisor.UserMemoryAdvisor.USER_ID, userId);
                })
                .call()
                .content();
    }

    /**
     * Agent 对话（图片 + 文本，多模态）
     */
    @CircuitBreaker(name = "llmCircuitBreaker", fallbackMethod = "fallbackWithImage")
    // @Retry(name = "llmRetry", fallbackMethod = "fallbackWithImage")  // 预留：同步调用可启用，流式调用不要用
    public String chatWithImage(String question, String sessionId, Long userId,
                                String imageUrl, String contentType,
                                Resource imageResource) {
        return chatClient.prompt()
                .system(agentImageSystemPrompt)
                .user(u -> u.text("![图片](" + imageUrl + ")\n\n" + question)
                        .media(MimeType.valueOf(contentType), imageResource))
                .options(OpenAiChatOptions.builder().model("qwen-vl-plus"))
                .tools(com.liwx.learning.agent.tool.RagTool.class,
                       com.liwx.learning.agent.tool.TimeTool.class,
                       com.liwx.learning.agent.tool.WeatherTool.class,
                       com.liwx.learning.agent.tool.UserQueryTool.class,
                       com.liwx.learning.agent.tool.ResearchTool.class)
                .advisors(a -> {
                    a.param(ChatMemory.CONVERSATION_ID, sessionId);
                    a.param(com.liwx.learning.rag.advisor.UserMemoryAdvisor.USER_ID, userId);
                })
                .call()
                .content();
    }

    private String fallback(String question, String sessionId, Long userId, Throwable t) {
        log.warn("LLM 调用熔断降级，question={}，原因：{}", question, t.getMessage());
        return "智能助手暂时不可用（" + t.getClass().getSimpleName() + "），请稍后再试。";
    }

    private String fallbackWithImage(String question, String sessionId, Long userId,
                                     String imageUrl, String contentType,
                                     Resource imageResource, Throwable t) {
        log.warn("LLM 图片对话熔断降级，question={}，原因：{}", question, t.getMessage());
        return "智能助手暂时不可用（" + t.getClass().getSimpleName() + "），请稍后再试。";
    }
}
