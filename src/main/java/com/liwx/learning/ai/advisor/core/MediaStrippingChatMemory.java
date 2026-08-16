package com.liwx.learning.ai.advisor.core;

import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

/**
 * 多模态消息脱壳 ChatMemory 包装器
 *
 * 问题：用户上传图片对话时，多模态 UserMessage 包含 base64 图片数据（几百 KB~几 MB），
 *       直接存进 SPRING_AI_CHAT_MEMORY 表会导致：
 *       1. 数据库膨胀（每条图片消息存一份 base64）
 *       2. 后续对话加载历史时，旧图片被重复发给模型，浪费 token
 *
 * 解决：包装 ChatMemory.add()，存之前把图片媒体数据剥离，只保留文本（文本中已有 markdown 图片 URL）
 *       模型本次调用能看到图片（通过 ChatClient 的 .media() 传入），但 ChatMemory 只存文本
 *
 * 面试一句话：用装饰器模式包装 ChatMemory，多模态消息只持久化文本部分，避免 base64 膨胀
 */
public class MediaStrippingChatMemory implements ChatMemory {

    private final ChatMemory delegate;

    public MediaStrippingChatMemory(ChatMemory delegate) {
        this.delegate = delegate;
    }

    @Override
    public void add(String conversationId, List<Message> messages) {
        // 存之前剥离媒体数据，只保留文本
        List<Message> textOnly = messages.stream()
                .map(this::stripMedia)
                .toList();
        delegate.add(conversationId, textOnly);
    }

    @Override
    public List<Message> get(String conversationId) {
        return delegate.get(conversationId);
    }

    @Override
    public void clear(String conversationId) {
        delegate.clear(conversationId);
    }

    /**
     * 剥离 UserMessage 中的媒体数据，只保留文本
     * 非 UserMessage（如 AssistantMessage、SystemMessage）原样返回
     */
    private Message stripMedia(Message msg) {
        if (msg instanceof UserMessage um && !um.getMedia().isEmpty()) {
            // 多模态消息 → 只取文本部分重新构造
            return new UserMessage(um.getText());
        }
        return msg;
    }
}
