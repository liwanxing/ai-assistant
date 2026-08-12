package com.liwx.learning.rag.service;

import com.liwx.learning.rag.entity.UserMemory;
import com.liwx.learning.rag.mapper.UserMemoryMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 用户长期记忆服务（MySQL + Milvus 双写）
 *
 * 数据流向：
 *   写入：MySQL 存原文 → 同时向量化存 Milvus（两个用同一个 ID 关联）
 *   检索：从 Milvus 语义搜索最相关的记忆 → 返回记忆内容
 *   删除：先删 Milvus 向量 → 再删 MySQL 记录
 *
 * 与 ChatMemory（短期记忆）的区别：
 *   ChatMemory 存对话内容，只在同一个 sessionId 内生效
 *   UserMemory 存用户偏好，跨所有会话永久生效
 */
@Slf4j
@Service
public class UserMemoryService {

    @Autowired
    private UserMemoryMapper userMemoryMapper;
    @Autowired
    private ChatModel chatModel;
    @Autowired
    private VectorStore vectorStore;

    /**
     * 检索与当前问题最相关的用户记忆，拼成 system prompt 片段
     *
     * 从 Milvus 语义搜索（不是全量），只取跟当前问题最相关的 top 3 条记忆
     * 用 filterExpression 过滤：type='user_memory' + userId=xxx，避免搜到文档向量
     */
    public String buildMemoryPrompt(Long userId, String question) {
        if (userId == null) {
            return "";
        }
        try {
            // 从 Milvus 语义搜索：当前问题最相关的用户记忆
            List<Document> docs = vectorStore.similaritySearch(SearchRequest.builder()
                    .query(question)
                    .topK(3)
                    .filterExpression("type == 'user_memory' && userId == '" + userId + "'")
                    .build());

            if (docs == null || docs.isEmpty()) {
                return "";
            }
            String memoryList = docs.stream()
                    .map(Document::getText)
                    .collect(Collectors.joining("\n- ", "- ", ""));
            return "\n\n以下是该用户的偏好信息，请在回答时参考：\n" + memoryList;
        } catch (Exception e) {
            log.warn("记忆检索失败（降级为无记忆）：userId={}, error={}", userId, e.getMessage());
            return "";
        }
    }

    /**
     * 异步提取记忆：对话完成后调用，不阻塞流式响应
     *
     * 提取逻辑：让 LLM 判断用户问题是否包含值得长期记住的偏好/信息
     *   - 值得记 → 提取为一句话 → 语义去重：跟已有记忆比对，相似的更新，全新的新增
     *   - 不值得 → 返回"无"，跳过
     *
     * @Async 保证异步执行，不影响 SSE 流式响应
     */
    @Async
    public void extractMemory(Long userId, String question) {
        if (userId == null || question == null || question.isBlank()) {
            return;
        }
        try {
            String result = chatModel.call(
                    "你是一个记忆提取助手。分析以下用户发言，判断是否包含值得长期记住的用户偏好或个人信息。\n\n" +
                    "值得记住的内容（举例）：\n" +
                    "- 用户称呼（如\"叫我李总\"\"我姓张\"）\n" +
                    "- 用户职业（如\"我是Java开发\"\"我做前端\"）\n" +
                    "- 回答偏好（如\"回答简洁一点\"\"用中文\"\"给代码示例\"）\n" +
                    "- 个人信息（如\"我在北京工作\"\"团队有5个人\"）\n\n" +
                    "不值得记住的内容：具体问题、闲聊、知识查询（如\"你好\"\"保险怎么理赔\"）\n\n" +
                    "用户发言：" + question + "\n\n" +
                    "如果值得记住，提取为一句话描述（如\"用户喜欢简洁的回答\"），只返回这句话。\n" +
                    "如果不值得记住，只返回\"无\"。"
            );

            if (result == null || result.isBlank() || result.trim().equals("无")) {
                return;
            }
            String memory = result.trim();

            // 语义去重：向量搜索已有记忆，判断是否高度相似
            List<Document> similarDocs = vectorStore.similaritySearch(SearchRequest.builder()
                    .query(memory)
                    .topK(1)
                    .similarityThreshold(0.85)
                    .filterExpression("type == 'user_memory' && userId == '" + userId + "'")
                    .build());

            if (similarDocs != null && !similarDocs.isEmpty()) {
                // 有高度相似的已有记忆 → 更新（用户改了偏好，如从"叫我李总"改成"叫我老李"）
                Long existingId = Long.parseLong(similarDocs.get(0).getId());
                // 1. 更新 MySQL 记录
                userMemoryMapper.updateContent(existingId, memory);
                // 2. Milvus 不支持原地改向量 → 删旧向量 + 写新向量
                vectorStore.delete(List.of(String.valueOf(existingId)));
                Document newDoc = Document.builder()
                        .id(String.valueOf(existingId))
                        .text(memory)
                        .metadata(Map.of("type", "user_memory", "userId", String.valueOf(userId)))
                        .build();
                vectorStore.add(List.of(newDoc));
                log.info("用户记忆已更新：userId={}, id={}, memory={}", userId, existingId, memory);
            } else {
                // 无相似 → 新增（全新的偏好）
                // 1. 先写 MySQL，拿到自增 ID（作为 Milvus document ID）
                UserMemory userMemory = new UserMemory();
                userMemory.setUserId(userId);
                userMemory.setContent(memory);
                userMemoryMapper.insert(userMemory);

                // 2. 再写 Milvus，用 MySQL 的 ID 作为 document ID，metadata 标记为用户记忆
                Document doc = Document.builder()
                        .id(String.valueOf(userMemory.getId()))
                        .text(memory)
                        .metadata(Map.of("type", "user_memory", "userId", String.valueOf(userId)))
                        .build();
                vectorStore.add(List.of(doc));
                log.info("用户记忆已双写：userId={}, memory={}, milvusId={}", userId, memory, userMemory.getId());
            }

        } catch (Exception e) {
            log.warn("记忆提取失败（不影响正常对话）：userId={}, error={}", userId, e.getMessage());
        }
    }

    /**
     * 查询用户所有记忆（前端管理页面用）
     */
    public List<UserMemory> listByUserId(Long userId) {
        return userMemoryMapper.selectByUserId(userId);
    }

    /**
     * 删除单条记忆：先删 Milvus 向量 → 再删 MySQL 记录
     */
    public void deleteMemory(Long memoryId) {
        // 1. 先删 Milvus 向量
        try {
            vectorStore.delete(List.of(String.valueOf(memoryId)));
        } catch (Exception e) {
            log.warn("Milvus 删除记忆失败（继续删 MySQL）：id={}, error={}", memoryId, e.getMessage());
        }
        // 2. 再删 MySQL 记录
        userMemoryMapper.deleteById(memoryId);
        log.info("用户记忆已删除：id={}", memoryId);
    }
}
