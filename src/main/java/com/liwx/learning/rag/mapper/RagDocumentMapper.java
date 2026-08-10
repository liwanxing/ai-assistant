package com.liwx.learning.rag.mapper;

import com.liwx.learning.rag.entity.RagDocument;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface RagDocumentMapper {

    int insert(RagDocument document);

    RagDocument selectById(@Param("id") Long id);

    List<RagDocument> selectAll();

    /**
     * 更新处理状态（异步处理完成后调用）
     */
    int updateStatus(@Param("id") Long id, @Param("status") String status,
                     @Param("chunkCount") Integer chunkCount, @Param("errorMessage") String errorMessage);

    /**
     * 软删除：标记 deleted = 1
     */
    int deleteById(@Param("id") Long id);
}
