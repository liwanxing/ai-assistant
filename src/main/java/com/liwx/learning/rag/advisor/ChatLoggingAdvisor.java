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
 *
 * 【和 before 的区别】
 * BaseAdvisor 的 before() 只能在「请求发给大模型之前」修改 request，改完 return 就完事，拿不到大模型的回答。
 * CallAdvisor 的 adviseCall() 是「环绕通知」（类似 Spring AOP 的 @Around）：
 *   - chain.nextCall() 之前 = BEFORE：可以修改请求
 *   - chain.nextCall()      = 真正调大模型（或调下一个 Advisor）
 *   - chain.nextCall() 之后 = AFTER：可以拿到大模型的回答（response），看或改都行
 *
 * 【每个 Advisor 都能拿到结果】
 * response 从最内层（最接近大模型）一层层冒泡返回，每经过一个 Advisor，该 Advisor 都能拿到。
 * 只是看你「用不用」这个结果——比如 SummaryAdvisor 只改 request 不看 response，MemoryAdvisor 既改 request（加载历史）又看 response（存回答到 DB）。
 *
 * 【执行顺序（洋葱模型）】
 * 请求进入 → MemoryAdvisor(order=最低) → SummaryAdvisor(order=500) → 本类(order=1000) → 大模型
 * 结果返回 ← MemoryAdvisor           ← SummaryAdvisor           ← 本类           ← 大模型
 *
 * order=1000：排在最后，离大模型最近，看到的是最终的、没有任何后续加工的输入和输出。
 */
@Slf4j
public class ChatLoggingAdvisor implements CallAdvisor, StreamAdvisor {

    /**
     * 同步调用：完整演示环绕通知的 before → 调用 → after
     */
    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        // BEFORE：请求发给大模型之前，此时 request 里已经是完整消息列表
        logRequest(request);

        // 真正调用：传给链中下一个 Advisor，最终到达大模型，返回结果
        ChatClientResponse response = chain.nextCall(request);

        // AFTER：拿到大模型的回答了，可以看（打日志）也可以改（比如脱敏）
        logResponse(response);

        return response;
    }

    /**
     * 流式调用（SSE）：和 adviseCall 一样是环绕，但 response 是 Flux（逐块返回，不是一次性）
     */
    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        // BEFORE：流式请求发出之前
        logRequest(request);

        // 真正调用：返回 Flux，大模型的回答会一块一块地推过来
        Flux<ChatClientResponse> flux = chain.nextStream(request);

        // AFTER：通过 Reactor 操作符拦截流式结果
        // doOnComplete：所有 chunk 发完时触发；doOnNext：每个 chunk 到达时触发；doOnError：异常时触发
        return flux
                .doOnComplete(() -> log.debug("========== 大模型响应完成 =========="))
                .doOnError(e -> log.error("大模型响应异常", e));
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
