package com.liwx.learning.rag.mapper;

import com.liwx.learning.rag.entity.UserMemory;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 用户长期记忆 Mapper
 */
@Mapper
public interface UserMemoryMapper {

    /** 查询某个用户的所有记忆（按时间倒序） */
    List<UserMemory> selectByUserId(@Param("userId") Long userId);

    /** 新增记忆（useGeneratedKeys 回填 id，作为 Milvus document ID） */
    int insert(UserMemory userMemory);

    /** 更新记忆内容（语义相似时更新，而非新增） */
    int updateContent(@Param("id") Long id, @Param("content") String content);

    /** 根据 ID 删除单条记忆 */
    int deleteById(@Param("id") Long id);

    /** 删除某用户所有记忆 */
    int deleteByUserId(@Param("userId") Long userId);
}
