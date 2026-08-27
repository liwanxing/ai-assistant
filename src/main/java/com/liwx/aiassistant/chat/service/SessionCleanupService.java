package com.liwx.aiassistant.chat.service;

import com.liwx.aiassistant.chat.advisor.core.SemanticCacheStore;
import com.liwx.aiassistant.chat.mapper.ChatSessionMapper;
import com.liwx.aiassistant.chat.mapper.ConversationSummaryMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 会话清理服务（历史数据生命周期管理）
 *
 * 职责：把一个会话的「四件套」完整删除，不留孤儿：
 *   ①聊天图片物理文件（uploads/chat-images/，从消息文本提取 URL 后删文件）
 *   ②消息原文（SPRING_AI_CHAT_MEMORY 表，经 ChatMemory.clear 删）
 *   ③会话摘要（rag_conversation_summary 表）
 *   ④会话记录（rag_chat_session 表——最后删，它是清理的「锚」）
 *
 * 锚的设计：会话记录放最后删。中途任何一步失败，这条会话下轮定时任务还能按
 * update_time 重新扫到、重删一遍——每步都是幂等的（删不存在的数据不报错），
 * 所以重删是安全的；若先删锚，失败后②③④就成永久孤儿。
 *
 * 两个入口共用同一套逻辑：
 *   ①用户手动删会话（RagController.deleteSession）——修复旧版只删②④、①③成孤儿的 bug
 *   ②定时清理（HistoryCleanupTask 触发）——最后活跃时间早于 retain-days 的会话
 *
 * 面试一句话：历史数据清理 = 以会话为单位四件套原子删除 + 锚定重试保证幂等 + 分批删除防大事务
 */
@Slf4j
@Service
public class SessionCleanupService {

    // 匹配消息文本中的 markdown 图片语法：![图片](/uploads/chat-images/xxx.png)
    // 与 RagController 展示消息时的解析规则保持一致
    private static final Pattern IMAGE_PATTERN = Pattern.compile("!\\[[^]]*]\\((/uploads/[^)]+)\\)");

    private final ChatMemory chatMemory;
    private final ChatSessionMapper chatSessionMapper;
    private final ConversationSummaryMapper summaryMapper;
    private final SemanticCacheStore semanticCacheStore;
    private final String uploadDir;
    private final int retainDays;
    private final int batchSize;

    public SessionCleanupService(ChatMemory chatMemory,
                                 ChatSessionMapper chatSessionMapper,
                                 ConversationSummaryMapper summaryMapper,
                                 SemanticCacheStore semanticCacheStore,
                                 @Value("${rag.upload-dir}") String uploadDir,
                                 @Value("${rag.cleanup.retain-days:180}") int retainDays,
                                 @Value("${rag.cleanup.batch-size:100}") int batchSize) {
        this.chatMemory = chatMemory;
        this.chatSessionMapper = chatSessionMapper;
        this.summaryMapper = summaryMapper;
        this.semanticCacheStore = semanticCacheStore;
        this.uploadDir = uploadDir;
        this.retainDays = retainDays;
        this.batchSize = batchSize;
    }

    /**
     * 定时清理入口：删除 retain-days 天未活跃的会话四件套 + 语义缓存过期条目物理清理
     *
     * 分批执行：每批 batchSize 个会话，删完再查下一批，直到查不到——
     * 一次性 DELETE 几万行是大事务（锁表 + binlog 风暴），分批每批都是小事务
     */
    public void cleanExpired() {
        LocalDateTime before = LocalDateTime.now().minusDays(retainDays);
        int totalSessions = 0;
        int totalImages = 0;

        while (true) {
            List<String> expiredIds = chatSessionMapper.selectExpiredIds(before, batchSize);
            if (expiredIds.isEmpty()) {
                break;
            }

            int deletedThisBatch = 0;
            for (String sessionId : expiredIds) {
                try {
                    totalImages += deleteSessionFully(sessionId);
                    totalSessions++;
                    deletedThisBatch++;
                } catch (Exception e) {
                    // 单个会话删除失败不影响整批：锚（会话记录）还在，下轮任务会重新扫到它重删
                    log.warn("会话 {} 清理失败（下轮定时任务会重试）：{}", sessionId, e.getMessage());
                }
            }

            // 防死循环：整批全部失败（如磁盘被锁/DB 故障）说明本轮删不动了，止损退出。
            // 不加这个保护的话：失败的会话一直占着过期列表，while 会无限循环查同一批
            if (deletedThisBatch == 0) {
                log.error("本轮清理整批 {} 个会话全部失败，疑似环境故障，止损退出等明天重试", expiredIds.size());
                break;
            }
        }

        // 顺手物理清理语义缓存过期条目（TTL 检查只是查询时忽略，条目本身不删会无限累积）
        semanticCacheStore.purgeExpired();

        log.info("定时清理完成：删除 {} 个会话（{} 天未活跃）、{} 张聊天图片", totalSessions, retainDays, totalImages);
    }

    /**
     * 彻底删除一个会话（四件套），返回删除的图片文件数
     * 删除顺序：图片 → 消息 → 摘要 → 会话记录（锚最后删，理由见类注释）
     *
     * @throws IOException 图片文件删除失败（Files.deleteIfExists 抛出，调用方决定是否重试）
     */
    public int deleteSessionFully(String sessionId) throws IOException {
        // 1. 先从消息里提取图片 URL 并删物理文件——消息删了就找不到文件名了，必须最先做
        int imageCount = 0;
        List<Message> messages = chatMemory.get(sessionId);
        for (Message msg : messages) {
            Matcher matcher = IMAGE_PATTERN.matcher(msg.getText());
            while (matcher.find()) {
                // URL 形如 /uploads/chat-images/xxx.png，物理路径是 {upload-dir}/chat-images/xxx.png
                String imageUrl = matcher.group(1);
                Path file = Path.of(uploadDir).toAbsolutePath()
                        .resolve(imageUrl.substring("/uploads/".length()));
                Files.deleteIfExists(file);  // 幂等：文件不存在不报错（重删安全的关键）
                imageCount++;
            }
        }

        // 2. 删消息原文（SPRING_AI_CHAT_MEMORY 表）
        chatMemory.clear(sessionId);
        // 3. 删会话摘要（rag_conversation_summary 表）
        summaryMapper.deleteBySessionId(sessionId);
        // 4. 删会话记录（rag_chat_session 表）——锚，最后删
        chatSessionMapper.deleteBySessionId(sessionId);

        log.debug("会话 {} 已彻底删除（含 {} 张图片）", sessionId, imageCount);
        return imageCount;
    }
}
