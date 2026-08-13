package com.liwx.learning.rag.advisor;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.metadata.Usage;

/**
 * Token 用量监控 Advisor：拦截模型响应，记录每次调用的 token 消耗
 *
 * 原理：Advisor 链是 AOP 环绕通知，chain.nextCall() 返回的 ChatClientResponse 里
 * 包含模型返回的 Usage 元数据（promptTokens / completionTokens / totalTokens），
 * 取出来打日志即可，不影响业务逻辑。
 *
 * 面试一句话：用 Advisor 链做 AOP 拦截，记录每次 AI 调用的 token 消耗，
 * 用于成本监控和性能分析
 */
@Slf4j
public class TokenUsageAdvisor implements CallAdvisor {

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        ChatClientResponse response = chain.nextCall(request);
        logUsage(request, response);
        return response;
    }

    /**
     * 从响应中提取 token 用量并记录日志
     */
    private void logUsage(ChatClientRequest request, ChatClientResponse response) {
        if (response == null || response.chatResponse() == null) {
            return;
        }
        var metadata = response.chatResponse().getMetadata();
        if (metadata == null || metadata.getUsage() == null) {
            return;
        }

        Usage usage = metadata.getUsage();
        Long userId = getUserIdFromContext(request);
        String sessionId = getSessionIdFromContext(request);

        log.info("Token 用量 | userId={} sessionId={} 输入={} 输出={} 总计={}",
                userId,
                sessionId,
                usage.getPromptTokens(),
                usage.getCompletionTokens(),
                usage.getTotalTokens());
    }

    private Long getUserIdFromContext(ChatClientRequest request) {
        Object value = request.context().get(UserMemoryAdvisor.USER_ID);
        if (value == null) return null;
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String getSessionIdFromContext(ChatClientRequest request) {
        Object value = request.context().get("chat_memory_conversation_id");
        return value != null ? value.toString() : "-";
    }

    @Override
    public String getName() {
        return "TokenUsageAdvisor";
    }

    @Override
    public int getOrder() {
        return 50;  // 最外层，包住所有 Advisor，确保能拿到最终响应
    }
}
