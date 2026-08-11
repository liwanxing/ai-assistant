package com.liwx.learning.rag.mapper;

import com.liwx.learning.rag.entity.ConversationSummary;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ConversationSummaryMapper {

    ConversationSummary selectBySessionId(@Param("sessionId") String sessionId);

    int upsert(@Param("sessionId") String sessionId,
               @Param("summary") String summary,
               @Param("summarizedUpTo") Integer summarizedUpTo);
}
