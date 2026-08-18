package com.liwx.learning.rag.mapper;

import com.liwx.learning.rag.entity.RagDocument;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
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
     * 查询卡死的 PROCESSING 文档：update_time 早于 before 还没出结果，
     * 说明任务已无人认领（死信无消费者 / @Async 重启丢 / 应用中途崩）——对账扫描用
     */
    List<RagDocument> selectStuckProcessing(@Param("before") LocalDateTime before);

    /**
     * 软删除：标记 deleted = 1
     */
    int deleteById(@Param("id") Long id);
}
