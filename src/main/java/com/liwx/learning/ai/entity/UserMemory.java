package com.liwx.learning.ai.entity;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 用户长期记忆实体
 * LLM 从对话中自动提取的用户偏好/个人信息，跨会话生效
 *
 * 双写关联：id 同时作为 Milvus 的 document ID，MySQL 和 Milvus 一一对应
 */
@Data
public class UserMemory {
    private Long id;
    private Long userId;
    private String content;
    private LocalDateTime createTime;
}
