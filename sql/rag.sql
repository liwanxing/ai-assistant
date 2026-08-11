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

-- =============================================
-- 对话摘要表：当对话超过窗口大小时，旧消息被压缩成摘要存这里
-- 避免长对话丢失上下文（MessageWindowChatMemory 超过 maxMessages 会直接丢弃旧消息）
-- =============================================

DROP TABLE IF EXISTS rag_conversation_summary;

CREATE TABLE rag_conversation_summary (
    id              BIGINT       PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    session_id      VARCHAR(36)  NOT NULL                COMMENT '会话ID（对应 ChatMemory 的 conversationId）',
    summary         TEXT                                 COMMENT 'LLM 生成的对话摘要',
    summarized_up_to INT          NOT NULL DEFAULT 0      COMMENT '已摘要到第几条消息（追踪进度，避免重复摘要）',
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    UNIQUE KEY uk_session (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='长对话摘要表';

-- =============================================
-- 会话管理表：记录每次对话的元信息（标题、时间），用于左侧历史会话列表展示
-- 和 SPRING_AI_CHAT_MEMORY（存消息内容）配合使用：
--   SPRING_AI_CHAT_MEMORY = 存聊天消息本身（Spring AI 自动管理）
--   rag_chat_session      = 存会话的标题、时间等展示信息（我们自己管理）
-- =============================================

DROP TABLE IF EXISTS rag_chat_session;

CREATE TABLE rag_chat_session (
    id              BIGINT       PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    session_id      VARCHAR(36)  NOT NULL                COMMENT '会话ID（对应 ChatMemory 的 conversationId）',
    title           VARCHAR(100) NOT NULL DEFAULT '新对话'  COMMENT '会话标题（取用户第一句话的前若干字）',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后活跃时间',
    UNIQUE KEY uk_session (session_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='会话管理表';
