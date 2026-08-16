package com.liwx.learning.rag.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 文档分段 Mapper：存储 chunk 原文 + MySQL FULLTEXT 关键词检索
 *
 * 和 Milvus 的分工：
 *   Milvus：存向量，做语义相似度检索（"意思接近"的 chunk）
 *   MySQL：存原文 + FULLTEXT INDEX，做关键词检索（"包含某个词"的 chunk）
 * 两者用同一个 chunk_id（doc{documentId}_{index}）关联
 */
@Mapper
public interface RagChunkMapper {

    /**
     * 批量插入分段
     */
    int batchInsert(@Param("chunks") List<Map<String, Object>> chunks);

    /**
     * 关键词检索：MySQL FULLTEXT INDEX + ngram 分词器
     * 返回 chunk_id 和 content，调用方根据 chunk_id 去重合并
     */
    List<Map<String, Object>> searchByKeyword(@Param("query") String query, @Param("limit") int limit);

    /**
     * 按文档ID删除全部分段
     */
    int deleteByDocumentId(@Param("documentId") Long documentId);

    /**
     * 查文档全部分段 ID（chunk_id 即 Milvus 主键 doc{documentId}_{index}）
     * 幂等清理用：MQ 至少一次投递可能重复消费，重跑前按此清单删 Milvus + MySQL 旧数据
     */
    List<String> selectChunkIdsByDocumentId(@Param("documentId") Long documentId);

    /**
     * 按位置批量取分段（RagTool 窗口扩容用）：(document_id, chunk_index) 元组 IN，
     * 一条 SQL 取回所有相邻段，不逐段查询（防 N+1）。
     * positions 每项是含 documentId / chunkIndex 两个键的 Map
     */
    List<Map<String, Object>> selectByPositions(@Param("positions") List<Map<String, Object>> positions);
}
