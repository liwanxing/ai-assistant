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
 * 自定义日志 Advisor：插在 Advisor 链中，拦截发给大模型的最终请求
 *
 * 与 Controller 里手动查 chatMemory 不同，这里看到的是 MemoryAdvisor 处理后的完整消息列表，
 * 也就是真正发给大模型的东西，100% 准确。
 *
 * order 设为 1000，确保在 MemoryAdvisor（order=HIGHEST_PRECEDENCE+200）之后执行。
 */
@Slf4j
public class ChatLoggingAdvisor implements CallAdvisor, StreamAdvisor {

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        logRequest(request);
        return chain.nextCall(request);
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        logRequest(request);
        return chain.nextStream(request);
    }

    private void logRequest(ChatClientRequest request) {
        List<Message> messages = request.prompt().getInstructions();
        log.info("=== 发送给大模型的消息（共{}条）===", messages.size());
        for (int i = 0; i < messages.size(); i++) {
            Message msg = messages.get(i);
            String text = msg.getText();
            String preview = text.length() > 80 ? text.substring(0, 80) + "..." : text;
            log.info("  {}. [{}] {}", i + 1, msg.getMessageType(), preview);
        }
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
