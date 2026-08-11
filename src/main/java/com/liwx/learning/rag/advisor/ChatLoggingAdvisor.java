package com.liwx.learning.rag.advisor;

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
 * 自定义日志 Advisor：拦截发给大模型的请求和返回的响应，打印完整链路日志
 */
@Slf4j
public class ChatLoggingAdvisor implements CallAdvisor, StreamAdvisor {

    /**
     * 流式调用（SSE）：本项目 RAG 问答走的就是这条路径
     * 环绕通知：before 打日志 → chain.nextStream 调大模型 → after 拦截响应
     */
    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        logRequest(request);
        Flux<ChatClientResponse> flux = chain.nextStream(request);
        return flux
                .doOnComplete(() -> log.debug("========== 大模型响应完成 =========="))
                .doOnError(e -> log.error("大模型响应异常", e));
    }

    /**
     * 同步调用：逻辑和流式一样（before 打日志 → 调大模型 → after 打日志）
     */
    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        logRequest(request);
        ChatClientResponse response = chain.nextCall(request);
        logResponse(response);
        return response;
    }

    /**
     * BEFORE 日志：打印发给大模型的完整消息列表
     */
    private void logRequest(ChatClientRequest request) {
        if (!log.isDebugEnabled()) {
            return;
        }
        List<Message> messages = request.prompt().getInstructions();
        StringBuilder sb = new StringBuilder();
        sb.append("\n========== 发送给大模型（共").append(messages.size()).append("条）==========");
        for (int i = 0; i < messages.size(); i++) {
            Message msg = messages.get(i);
            String text = msg.getText();
            String preview = text.length() > 80 ? text.substring(0, 80) + "..." : text;
            sb.append("\n  ").append(i + 1).append(". [")
              .append(msg.getMessageType()).append("] ").append(preview);
        }
        sb.append("\n==========================================");
        log.debug(sb.toString());
    }

    /**
     * AFTER 日志：打印大模型的回答（只有同步调用 adviseCall 能拿到，流式走的是 doOnComplete）
     */
    private void logResponse(ChatClientResponse response) {
        if (!log.isDebugEnabled() || response == null) {
            return;
        }
        String text = response.chatResponse().getResult().getOutput().getText();
        String preview = (text != null && text.length() > 100) ? text.substring(0, 100) + "..." : text;
        StringBuilder sb = new StringBuilder();
        sb.append(System.lineSeparator()).append("========== 大模型回答 ==========");
        sb.append(System.lineSeparator()).append("  ").append(preview);
        sb.append(System.lineSeparator()).append("================================");
        log.debug(sb.toString());
    }

    @Override
    public String getName() {
        return "ChatLoggingAdvisor";
    }

    @Override
    public int getOrder() {
        return 1000;
    }
}
