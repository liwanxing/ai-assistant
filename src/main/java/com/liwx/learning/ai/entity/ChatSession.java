package com.liwx.learning.ai.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话实体：记录每次对话的元信息，用于左侧历史会话列表展示
 * 和 SPRING_AI_CHAT_MEMORY 配合：
 * - SPRING_AI_CHAT_MEMORY 存消息内容（Spring AI 自动管理）
 * - rag_chat_session 存标题、时间等展示信息
 */
@Data
public class ChatSession {
    private Long id;
    private String sessionId;
    private String title;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
