package com.liwx.learning.rag.advisor;

import com.liwx.learning.rag.entity.ConversationSummary;
import com.liwx.learning.rag.mapper.ConversationSummaryMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

/**
 * 对话摘要 Advisor：当历史消息超过阈值时，自动把旧消息压缩成摘要，避免长对话丢失上下文
 *
 * 工作原理：
 * 1. maxMessages=30（MemoryAdvisor 保留30条），keepRecent=20（最终发给大模型只保留最近20条）
 * 2. 超过20条的非系统消息 = 溢出部分，调用小模型压缩成摘要
 * 3. 摘要拼到 SYSTEM 消息里，旧消息从 prompt 中移除
 * 4. 最终大模型收到的是：[SYSTEM + 摘要] + [最近20条消息]
 *
 * order=500：在 MemoryAdvisor（加载历史）之后、ChatLoggingAdvisor（打日志）之前执行
 */
@Slf4j
public class ConversationSummaryAdvisor implements CallAdvisor, StreamAdvisor {

    private final ChatModel chatModel;
    private final ConversationSummaryMapper summaryMapper;
    private final int keepRecent;

    private static final String SUMMARY_PREFIX = "\n\n【之前对话摘要】";

    public ConversationSummaryAdvisor(ChatModel chatModel, ConversationSummaryMapper summaryMapper, int keepRecent) {
        this.chatModel = chatModel;
        this.summaryMapper = summaryMapper;
        this.keepRecent = keepRecent;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        ChatClientRequest processed = processSummary(request);
        return chain.nextCall(processed);
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        ChatClientRequest processed = processSummary(request);
        return chain.nextStream(processed);
    }

    /**
     * 核心逻辑：检查消息数量，溢出则摘要，注入摘要到 system 消息
     */
    private ChatClientRequest processSummary(ChatClientRequest request) {
        String sessionId = (String) request.context().get("chat_memory_conversation_id");
        if (sessionId == null) {
            return request;
        }

        List<Message> instructions = request.prompt().getInstructions();

        // 统计非系统消息数量（系统消息不参与计数）
        List<Message> nonSystemMessages = new ArrayList<>();
        for (Message msg : instructions) {
            if (msg.getMessageType() != MessageType.SYSTEM) {
                nonSystemMessages.add(msg);
            }
        }

        // 未超过阈值：只注入已有摘要（如果有）
        if (nonSystemMessages.size() <= keepRecent) {
            ConversationSummary existing = summaryMapper.selectBySessionId(sessionId);
            if (existing != null && existing.getSummary() != null) {
                return injectSummary(request, existing.getSummary(), 0);
            }
            return request;
        }

        // 溢出：把多出来的旧消息压缩成摘要
        int overflowCount = nonSystemMessages.size() - keepRecent;
        List<Message> overflowMessages = nonSystemMessages.subList(0, overflowCount);
        log.debug("对话 {} 消息数={}，溢出 {} 条，触发摘要压缩", sessionId, nonSystemMessages.size(), overflowCount);

        ConversationSummary existing = summaryMapper.selectBySessionId(sessionId);

        String newSummary;
        try {
            newSummary = doSummarize(existing, overflowMessages);
        } catch (Exception e) {
            log.warn("摘要生成失败，跳过本次压缩: {}", e.getMessage());
            // 失败时仍注入已有摘要
            if (existing != null && existing.getSummary() != null) {
                return injectSummary(request, existing.getSummary(), 0);
            }
            return request;
        }

        // 存摘要，记录已摘要到第几条
        summaryMapper.upsert(sessionId, newSummary, nonSystemMessages.size());

        // 修改 prompt：注入摘要 + 移除溢出消息
        return injectSummary(request, newSummary, overflowCount);
    }

    /**
     * 调用大模型生成摘要（用 ChatModel 直接调，绕过 Advisor 链避免递归）
     */
    private String doSummarize(ConversationSummary existing, List<Message> overflowMessages) {
        StringBuilder promptText = new StringBuilder();
        promptText.append("你是一个对话摘要助手。请将以下对话压缩成一段不超过200字的中文摘要，")
                .append("保留用户的核心问题、关键结论和重要上下文。只输出摘要内容，不要加任何前缀。\n\n");

        if (existing != null && existing.getSummary() != null) {
            promptText.append("已有摘要：\n").append(existing.getSummary()).append("\n\n");
            promptText.append("请结合已有摘要和以下新增对话，生成更新后的完整摘要：\n\n");
        }

        promptText.append("新增对话：\n");
        for (Message msg : overflowMessages) {
            String text = msg.getText();
            // 每条消息最多取200字参与摘要，避免 prompt 过长
            String preview = text.length() > 200 ? text.substring(0, 200) + "..." : text;
            promptText.append("[").append(msg.getMessageType()).append("] ")
                    .append(preview).append("\n");
        }

        ChatResponse response = chatModel.call(new Prompt(promptText.toString()));
        return response.getResult().getOutput().getText();
    }

    /**
     * 修改 prompt：把摘要拼到 SYSTEM 消息末尾，移除溢出的旧消息
     *
     * @param overflowCount 需要移除的非系统消息数量（0 表示不移除，只注入摘要）
     */
    private ChatClientRequest injectSummary(ChatClientRequest request, String summary, int overflowCount) {
        List<Message> instructions = request.prompt().getInstructions();
        List<Message> newInstructions = new ArrayList<>();

        int nonSystemSeen = 0;
        for (Message msg : instructions) {
            if (msg.getMessageType() == MessageType.SYSTEM) {
                // SYSTEM 消息：拼上摘要（先清理可能已有的旧摘要，避免重复）
                String systemText = msg.getText();
                int idx = systemText.indexOf(SUMMARY_PREFIX);
                if (idx >= 0) {
                    systemText = systemText.substring(0, idx);
                }
                newInstructions.add(new SystemMessage(systemText + SUMMARY_PREFIX + summary));
            } else {
                nonSystemSeen++;
                if (nonSystemSeen > overflowCount) {
                    newInstructions.add(msg); // 保留最近的消息
                }
                // overflowCount 之前的消息被跳过（已压缩成摘要）
            }
        }

        return request.mutate()
                .prompt(Prompt.builder().messages(newInstructions).build())
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
