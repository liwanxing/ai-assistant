package com.liwx.aiassistant.chat.mapper;

import com.liwx.aiassistant.chat.entity.ConversationSummary;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ConversationSummaryMapper {

    ConversationSummary selectBySessionId(@Param("sessionId") String sessionId);

    int upsert(@Param("sessionId") String sessionId,
               @Param("summary") String summary,
               @Param("summarizedUpTo") Integer summarizedUpTo);

    /**
     * 删除会话摘要：用户删会话 / 定时清理过期会话时调用，防止摘要表残留孤儿记录
     */
    int deleteBySessionId(@Param("sessionId") String sessionId);
}
