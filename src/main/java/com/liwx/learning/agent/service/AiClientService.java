package com.liwx.learning.agent.service;

import com.liwx.learning.agent.tool.RagTool;
import com.liwx.learning.agent.tool.ResearchTool;
import com.liwx.learning.agent.tool.TimeTool;
import com.liwx.learning.agent.tool.UserQueryTool;
import com.liwx.learning.agent.tool.WeatherTool;
import com.liwx.learning.rag.advisor.UserMemoryAdvisor;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import lombok.extern.slf4j.Slf4j;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

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
    private String agentSystemPrompt;
    private String agentImageSystemPrompt;

    @Value("classpath:prompts/agent-system.st")
    private Resource agentSystemPromptResource;

    @Value("classpath:prompts/agent-image-system.st")
    private Resource agentImageSystemPromptResource;

    /**
     * Agent 可用工具集（对内 Function Calling）
     * 按 Class 注册：Spring AI 自动从 Spring 容器解析对应的 @Component Bean，依赖注入完好，
     * 且不用在构造函数里堆 5 个工具参数
     * 与对外 MCP 暴露是两条独立通道：这里给本项目的千问模型用；外部客户端走 RagMcpTools（@McpTool）
     */
    private static final Class<?>[] AGENT_TOOLS = {
            RagTool.class, TimeTool.class, WeatherTool.class, UserQueryTool.class, ResearchTool.class
    };

    public AiClientService(ChatClient chatClient) {
        this.chatClient = chatClient;
    }

    @PostConstruct
    void loadPrompts() throws IOException {
        this.agentSystemPrompt = agentSystemPromptResource.getContentAsString(StandardCharsets.UTF_8);
        this.agentImageSystemPrompt = agentImageSystemPromptResource.getContentAsString(StandardCharsets.UTF_8);
    }

    /**
     * Agent 对话（文本）
     *
     * @CircuitBreaker：连续失败超阈值时自动熔断，返回降级提示，不傻等超时
     * 配置在 application.yml 的 resilience4j.circuitbreaker.instances.llmCircuitBreaker
     */
    @CircuitBreaker(name = "llmCircuitBreaker", fallbackMethod = "fallback")
    public String chat(String question, String sessionId, Long userId) {
        return chatClient.prompt()
                .system(agentSystemPrompt)
                .user(question)
                .tools(AGENT_TOOLS)
                .advisors(a -> {
                    a.param(ChatMemory.CONVERSATION_ID, sessionId);
                    a.param(UserMemoryAdvisor.USER_ID, userId);
                })
                .call()
                .content();
    }

    /**
     * Agent 对话（图片 + 文本，多模态）
     */
    @CircuitBreaker(name = "llmCircuitBreaker", fallbackMethod = "fallbackWithImage")
    public String chatWithImage(String question, String sessionId, Long userId,
                                String imageUrl, String contentType,
                                Resource imageResource) {
        return chatClient.prompt()
                .system(agentImageSystemPrompt)
                .user(u -> u.text("![图片](" + imageUrl + ")\n\n" + question)
                        .media(MimeType.valueOf(contentType), imageResource))
                .options(OpenAiChatOptions.builder().model("qwen-vl-plus"))
                .tools(AGENT_TOOLS)
                .advisors(a -> {
                    a.param(ChatMemory.CONVERSATION_ID, sessionId);
                    a.param(UserMemoryAdvisor.USER_ID, userId);
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
