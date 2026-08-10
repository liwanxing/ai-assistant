package com.liwx.learning.rag.entity;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.time.LocalDateTime;

/**
 * RAG 文档实体：记录用户上传的文档元信息和处理状态
 * <p>
 * 和 Milvus 的分工：
 * - MySQL rag_document 表：记录"谁上传了什么文件、处理到哪一步了"
 * - Milvus 向量库：存切分后的 chunk 向量，用于相似度检索
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class RagDocument {
    private Long id;
    private String fileName;
    private String filePath;
    private Long fileSize;
    private String fileType;
    private Integer chunkCount;
    private String status;
    private String errorMessage;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private Integer deleted;
}
