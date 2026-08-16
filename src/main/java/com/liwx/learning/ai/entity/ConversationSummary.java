package com.liwx.learning.ai.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 对话摘要实体：长对话中旧消息被压缩后的摘要，按 sessionId 关联
 */
@Data
public class ConversationSummary {
    private Long id;
    private String sessionId;
    private String summary;
    private Integer summarizedUpTo;
    private LocalDateTime updateTime;
}
