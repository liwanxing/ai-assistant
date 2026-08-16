package com.liwx.learning.agent.service;

import com.liwx.learning.rag.advisor.UserMemoryAdvisor;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import org.springframework.util.MimeType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * AI 调用 Service 层
 * 职责：封装所有 ChatClient 调用 + Resilience4j 弹性保护
 * Controller 只管 HTTP 协议（限流、会话管理、SSE 转换），不碰 LLM 调用细节
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiClientService {

    /**
     * 构造器注入（Spring 官方推荐的当前主流写法）：
     * - 依赖声明为 final：构造完成即就绪，之后不可变
     * - @RequiredArgsConstructor：Lombok 为所有 final 字段自动生成构造函数，
     *   等价于手写 public AiClientService(ChatClient chatClient, ToolRegistryService toolRegistryService) { ... }
     * - Spring 4.3+ 单构造函数场景连 @Autowired 都不用写，容器自动注入
     * - 字段声明即"我要什么依赖"，属性与构造器合为一体，无需再单独手写构造函数
     */
    private final ChatClient chatClient;
    private final ToolRegistryService toolRegistryService;
    private String agentSystemPrompt;
    private String agentImageSystemPrompt;

    @Value("classpath:prompts/agent-system.st")
    private Resource agentSystemPromptResource;

    @Value("classpath:prompts/agent-image-system.st")
    private Resource agentImageSystemPromptResource;

    @PostConstruct
    void loadPrompts() throws IOException {
        this.agentSystemPrompt = agentSystemPromptResource.getContentAsString(StandardCharsets.UTF_8);
        this.agentImageSystemPrompt = agentImageSystemPromptResource.getContentAsString(StandardCharsets.UTF_8);
    }

    /**
     * Agent 对话（文本）
     *
     * \@CircuitBreaker：连续失败超阈值时自动熔断，返回降级提示，不傻等超时
     * .call() 就是一次LLM的 HTTP 请求，和调高德 API 没本质区别，同样会挂，同样需要熔断。
     * 配置在 application.yml 的 resilience4j.circuitbreaker.instances.llmCircuitBreaker
     */
    @CircuitBreaker(name = "llmCircuitBreaker", fallbackMethod = "fallback")
    public String chat(String question, String sessionId, Long userId) {
        // 工具按请求动态筛选（常驻 + 权限过滤 + 向量预筛），不再全量注册，见 ToolRegistryService
        // .tools(Object...) 是 2.0 统一入口：List 会被自动迭代分发，每个 ToolCallback 直接注册
        List<ToolCallback> tools = toolRegistryService.selectTools(question, userId);
        return chatClient.prompt()
                .system(agentSystemPrompt)
                .user(question)
                .tools(tools)
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
        // 视觉模型做工具决策本来就弱，更要只带相关工具，减小候选集
        List<ToolCallback> tools = toolRegistryService.selectTools(question, userId);
        return chatClient.prompt()
                .system(agentImageSystemPrompt)
                .user(u -> u.text("![图片](" + imageUrl + ")\n\n" + question)
                        .media(MimeType.valueOf(contentType), imageResource))
                .options(OpenAiChatOptions.builder().model("qwen-vl-plus"))
                .tools(tools)
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
