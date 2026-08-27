package com.liwx.aiassistant.chat.advisor;

import com.liwx.aiassistant.chat.advisor.core.UserMemoryService;
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
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

/**
 * 用户长期记忆 Advisor：检索用户偏好注入 SYSTEM，对话完成后异步提取新偏好
 *
 * 数据变化示例：
 *   输入：  SYSTEM: 你是一个知识库问答助手...
 *           USER:   请假怎么请？
 *   输出：  SYSTEM: 你是一个知识库问答助手...
 *                  用户偏好：用户喜欢简洁的回答
 *           USER:   请假怎么请？
 *   （流完成后异步提取：调 LLM 判断用户发言是否含偏好，有则 MySQL + Milvus 双写）
 */
@Slf4j
public class UserMemoryAdvisor implements CallAdvisor, StreamAdvisor {

    private final UserMemoryService userMemoryService;

    /** Advisor 上下文中 userId 的 key（类似 ChatMemory.CONVERSATION_ID） */
    public static final String USER_ID = "user_id";

    private static final String MEMORY_PREFIX = "\n\n以下是该用户的偏好信息，请在回答时参考：\n";

    public UserMemoryAdvisor(UserMemoryService userMemoryService) {
        this.userMemoryService = userMemoryService;
    }

    /**
     * 流式调用（SSE）：本项目 RAG 问答走的就是这条路径
     * 环绕通知：before 注入记忆 → chain.nextStream 调大模型 → after 异步提取记忆
     */
    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        // BEFORE：注入用户记忆到 SYSTEM 消息
        ChatClientRequest processed = injectMemory(request);
        // AFTER：流式完成后异步提取记忆
        return chain.nextStream(processed)
                .doOnComplete(() -> extractMemoryFromRequest(processed));
    }

    /**
     * 同步调用：逻辑和流式一样
     */
    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        ChatClientRequest processed = injectMemory(request);
        ChatClientResponse response = chain.nextCall(processed);
        extractMemoryFromRequest(processed);
        return response;
    }

    /**
     * 注入用户记忆：从上下文取 userId，从 Milvus 语义搜索相关记忆，拼到 SYSTEM 消息末尾
     */
    private ChatClientRequest injectMemory(ChatClientRequest request) {
        Long userId = getUserIdFromContext(request);
        if (userId == null) {
            return request;
        }

        // 取用户最后一句话作为检索 query
        String question = getLastUserMessage(request);
        if (question == null) {
            return request;
        }

        // 从 Milvus 语义搜索最相关的用户记忆
        String memoryPrompt = userMemoryService.buildMemoryPrompt(userId, question);
        if (memoryPrompt == null || memoryPrompt.isBlank()) {
            return request;
        }

        // 拼到 SYSTEM 消息末尾（先清理可能已有的旧记忆，避免重复注入）
        List<Message> instructions = request.prompt().getInstructions();
        List<Message> newInstructions = new ArrayList<>();
        for (Message msg : instructions) {
            if (msg.getMessageType() == MessageType.SYSTEM) {
                String systemText = msg.getText();
                int idx = systemText.indexOf(MEMORY_PREFIX);
                if (idx >= 0) {
                    systemText = systemText.substring(0, idx);
                }
                newInstructions.add(new SystemMessage(systemText + memoryPrompt));
            } else {
                newInstructions.add(msg);
            }
        }

        // 必须用 prompt().mutate() 保留 chatOptions（含 toolCallbacks），
        // 否则 ToolCallingAdvisor 注册的 tools 会丢失
        return request.mutate()
                .prompt(request.prompt().mutate().messages(newInstructions).build())
                .build();
    }

    /**
     * 异步提取记忆：从上下文取 userId + 用户问题，异步调 LLM 提取偏好
     */
    private void extractMemoryFromRequest(ChatClientRequest request) {
        Long userId = getUserIdFromContext(request);
        if (userId == null) {
            return;
        }
        String question = getLastUserMessage(request);
        if (question == null) {
            return;
        }
        userMemoryService.extractMemory(userId, question);
    }

    /** 从 Advisor 上下文取 userId */
    private Long getUserIdFromContext(ChatClientRequest request) {
        Object value = request.context().get(USER_ID);
        if (value == null) {
            return null;
        }
        try {
            return Long.parseLong(value.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** 取 prompt 中最后一条 USER 消息的文本 */
    private String getLastUserMessage(ChatClientRequest request) {
        List<Message> instructions = request.prompt().getInstructions();
        for (int i = instructions.size() - 1; i >= 0; i--) {
            Message msg = instructions.get(i);
            if (msg.getMessageType() == MessageType.USER) {
                return msg.getText();
            }
        }
        return null;
    }

    @Override
    public String getName() {
        return "UserMemoryAdvisor";
    }

    @Override
    public int getOrder() {
        return 100;
    }
}
