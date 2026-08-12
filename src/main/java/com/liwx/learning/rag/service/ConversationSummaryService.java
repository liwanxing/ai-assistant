package com.liwx.learning.rag.service;

import com.liwx.learning.rag.entity.ConversationSummary;
import com.liwx.learning.rag.mapper.ConversationSummaryMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 对话摘要核心逻辑
 *
 * 职责：对话超过窗口阈值时，把旧消息压缩成摘要，最近的保留原样
 * 解耦说明：核心逻辑在这里，ConversationSummaryAdvisor 只是薄触发器，
 *          将来走其他模型 Agent 时可直接调本 Service 复用
 *
 * 数据变化示例（keepRecent=20，当前已有21条 USER/ASSISTANT 消息）：
 *   压缩前：SYSTEM + [USER, ASSISTANT] x 21 = 42条消息
 *   压缩后：SYSTEM + 【之前对话摘要】第1轮的对话内容... + [第2~21轮保留原样]
 */
@Slf4j
@Service
public class ConversationSummaryService {

    @Autowired
    private ChatModel chatModel;
    @Autowired
    private ConversationSummaryMapper summaryMapper;

    private final int keepRecent;

    private static final String SUMMARY_PREFIX = "\n\n【之前对话摘要】";

    public ConversationSummaryService(ChatModel chatModel, ConversationSummaryMapper summaryMapper) {
        this.chatModel = chatModel;
        this.summaryMapper = summaryMapper;
        this.keepRecent = 20;
    }

    /**
     * 处理摘要：检查消息数量，溢出则压缩，注入摘要到 system 消息
     *
     * @param sessionId   会话ID
     * @param instructions 完整消息列表（SYSTEM + 历史 + 当前）
     * @return 处理后的 instructions（摘要注入到 SYSTEM，溢出消息被移除）
     */
    public List<Message> processSummary(String sessionId, List<Message> instructions) {
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
                return injectSummary(instructions, existing.getSummary(), 0);
            }
            return instructions;
        }

        // 溢出：把多出来的旧消息压缩成摘要
        int overflowCount = nonSystemMessages.size() - keepRecent;
        List<Message> overflowMessages = nonSystemMessages.subList(0, overflowCount);
        log.debug("对话 {} 消息数={}，溢出 {} 条，触发摘要压缩", sessionId, nonSystemMessages.size(), overflowCount);

        ConversationSummary existing = summaryMapper.selectBySessionId(sessionId);

        // 把已有摘要 + 本次溢出的旧消息一起传给大模型，生成更新后的完整摘要
        String newSummary;
        try {
            newSummary = doSummarize(existing, overflowMessages);
        } catch (Exception e) {
            log.warn("摘要生成失败，跳过本次压缩: {}", e.getMessage());
            if (existing != null && existing.getSummary() != null) {
                return injectSummary(instructions, existing.getSummary(), 0);
            }
            return instructions;
        }

        // 存摘要，记录已摘要到第几条
        summaryMapper.upsert(sessionId, newSummary, nonSystemMessages.size());

        // 修改 prompt：注入摘要 + 移除溢出消息
        return injectSummary(instructions, newSummary, overflowCount);
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
     * 修改消息列表：把摘要拼到 SYSTEM 消息末尾，移除溢出的旧消息
     *
     * @param instructions   原始消息列表
     * @param summary        摘要内容
     * @param overflowCount  需要移除的非系统消息数量（0 表示不移除，只注入摘要）
     * @return 处理后的消息列表
     */
    private List<Message> injectSummary(List<Message> instructions, String summary, int overflowCount) {
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

        return newInstructions;
    }

    /**
     * 查询某个会话的已有摘要（供外部调用链复用）
     */
    public String getSummary(String sessionId) {
        ConversationSummary existing = summaryMapper.selectBySessionId(sessionId);
        return existing != null ? existing.getSummary() : null;
    }
}
