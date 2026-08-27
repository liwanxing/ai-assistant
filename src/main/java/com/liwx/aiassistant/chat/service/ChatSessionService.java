package com.liwx.aiassistant.chat.service;

import com.liwx.aiassistant.chat.entity.ChatSession;
import com.liwx.aiassistant.chat.mapper.ChatSessionMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 会话管理服务：处理会话的创建、查询、删除等业务逻辑
 * Controller 只管调这里，不直接操作 Mapper 和 ChatMemory
 */
@Slf4j
@Service
public class ChatSessionService {

    private final ChatSessionMapper chatSessionMapper;
    private final ChatMemory chatMemory;

    public ChatSessionService(ChatSessionMapper chatSessionMapper, ChatMemory chatMemory) {
        this.chatSessionMapper = chatSessionMapper;
        this.chatMemory = chatMemory;
    }

    /**
     * 创建或更新会话：首次提问自动创建（标题取用户问题前 20 字），已有会话刷新活跃时间
     */
    public void createOrUpdateSession(String sessionId, String question) {
        ChatSession existing = chatSessionMapper.selectBySessionId(sessionId);
        if (existing == null) {
            String title = question.length() > 20 ? question.substring(0, 20) + "..." : question;
            chatSessionMapper.insertIfNotExists(sessionId, title);
        } else {
            chatSessionMapper.updateActiveTime(sessionId);
        }
    }

    /**
     * 查询所有会话，按最后活跃时间倒序
     */
    public List<ChatSession> listSessions() {
        return chatSessionMapper.selectAll();
    }

    /**
     * 查询某个会话的聊天消息，转成前端需要的格式（过滤 SYSTEM 消息）
     */
    public List<Map<String, String>> getSessionMessages(String sessionId) {
        List<Message> history = chatMemory.get(sessionId);
        List<Map<String, String>> result = new ArrayList<>();
        for (Message msg : history) {
            if (msg.getMessageType() == MessageType.SYSTEM) {
                continue;
            }
            Map<String, String> item = new HashMap<>();
            item.put("role", msg.getMessageType() == MessageType.USER ? "user" : "ai");
            item.put("content", msg.getText());
            result.add(item);
        }
        return result;
    }

    /**
     * 删除会话：删 rag_chat_session 记录 + 清空 ChatMemory 里的消息
     */
    public void deleteSession(String sessionId) {
        chatSessionMapper.deleteBySessionId(sessionId);
        chatMemory.clear(sessionId);
        log.info("会话已删除, sessionId={}", sessionId);
    }
}
