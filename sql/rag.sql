-- =============================================
-- RAG 文档管理建表脚本
-- 记录用户上传的文档元信息，Milvus 存向量，MySQL 存文档状态
-- =============================================

USE liwx_learning;

DROP TABLE IF EXISTS rag_document;

CREATE TABLE rag_document (
    id            BIGINT       PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    file_name     VARCHAR(255) NOT NULL                COMMENT '原始文件名',
    file_path     VARCHAR(500) NOT NULL                COMMENT '本地存储路径（如 ./uploads/xxx.pdf）',
    file_size     BIGINT       NOT NULL DEFAULT 0      COMMENT '文件大小（字节）',
    file_type     VARCHAR(20)           DEFAULT NULL    COMMENT '文件类型（pdf/txt/docx）',
    chunk_count   INT          NOT NULL DEFAULT 0      COMMENT '切分后的向量块数',
    status        VARCHAR(20)  NOT NULL DEFAULT 'PROCESSING' COMMENT '处理状态 PROCESSING/SUCCESS/FAILED',
    error_message VARCHAR(500)          DEFAULT NULL    COMMENT '失败原因（status=FAILED 时才有值）',
    create_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time   DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    deleted       TINYINT      NOT NULL DEFAULT 0      COMMENT '逻辑删除 0未删除 1已删除'
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='RAG文档管理表';
