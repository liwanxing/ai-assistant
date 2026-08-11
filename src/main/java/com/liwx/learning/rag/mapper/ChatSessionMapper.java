package com.liwx.learning.rag.mapper;

import com.liwx.learning.rag.entity.ChatSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface ChatSessionMapper {

    /**
     * 查询所有会话，按最后活跃时间倒序（最近聊过的排最前）
     */
    List<ChatSession> selectAll();

    /**
     * 按 sessionId 查询（判断会话是否已存在）
     */
    ChatSession selectBySessionId(@Param("sessionId") String sessionId);

    /**
     * 新建会话：不存在才插入（INSERT IGNORE 防重复）
     */
    int insertIfNotExists(@Param("sessionId") String sessionId, @Param("title") String title);

    /**
     * 更新最后活跃时间（每次发消息时刷新）
     */
    int updateActiveTime(@Param("sessionId") String sessionId);

    /**
     * 删除会话（同时删 rag_chat_session 和 SPRING_AI_CHAT_MEMORY 里的记录）
     */
    int deleteBySessionId(@Param("sessionId") String sessionId);
}
