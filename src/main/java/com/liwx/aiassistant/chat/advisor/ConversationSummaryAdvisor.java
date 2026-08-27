package com.liwx.aiassistant.chat.advisor;

import com.liwx.aiassistant.chat.advisor.core.ConversationSummaryService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import reactor.core.publisher.Flux;

import java.util.List;

/**
 * 对话摘要 Advisor（薄触发器）
 *
 * 职责：只负责从 Advisor 上下文取 sessionId，调 ConversationSummaryService 处理摘要
 * 核心逻辑全部在 ConversationSummaryService 中，可被任意调用链复用
 *
 * adviseStream/adviseCall 是环绕通知（类似 Spring AOP @Around）：
 *   chain.nextStream() 之前 = before（压缩摘要）
 *   本 Advisor 只改 request，不需要看 response
 */
@Slf4j
public class ConversationSummaryAdvisor implements CallAdvisor, StreamAdvisor {

    private final ConversationSummaryService summaryService;

    public ConversationSummaryAdvisor(ConversationSummaryService summaryService) {
        this.summaryService = summaryService;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        ChatClientRequest processed = processSummary(request);
        return chain.nextStream(processed);
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        ChatClientRequest processed = processSummary(request);
        return chain.nextCall(processed);
    }

    /**
     * 调 Service 处理摘要，把返回的消息列表写回 request
     */
    private ChatClientRequest processSummary(ChatClientRequest request) {
        String sessionId = (String) request.context().get("chat_memory_conversation_id");
        if (sessionId == null) {
            return request;
        }

        List<Message> instructions = request.prompt().getInstructions();
        List<Message> processed = summaryService.processSummary(sessionId, instructions);

        if (processed == instructions) {
            // 没有变化（未超过阈值且无已有摘要），直接返回
            return request;
        }

        // 必须用 prompt().mutate() 保留 chatOptions（含 toolCallbacks），
        // 否则 ToolCallingAdvisor 注册的 tools 会丢失
        return request.mutate()
                .prompt(request.prompt().mutate().messages(processed).build())
                .build();
    }

    @Override
    public String getName() {
        return "ConversationSummaryAdvisor";
    }

    @Override
    public int getOrder() {
        return 500;
    }
}
