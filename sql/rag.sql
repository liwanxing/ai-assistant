-- =============================================
-- RAG 文档管理建表脚本
-- 记录用户上传的文档元信息，Milvus 存向量，MySQL 存文档状态
-- =============================================

USE ai_assistant;

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

-- =============================================
-- 用户长期记忆表：LLM 从对话中自动提取的用户偏好/个人信息
-- 跨会话生效：不管开哪个新对话，AI 都记得这些偏好
--
-- 双写模式（MySQL + Milvus）：
--   MySQL  存记忆原文（可靠存储，支持 CRUD）
--   Milvus 存记忆向量（语义检索，找最相关的记忆）
--   两者用 user_memory.id = Milvus document ID 关联
--   类似 MySQL + ES 的关系：ES 做搜索，MySQL 做数据主存储
-- =============================================

-- =============================================
-- 文档分段表：每个 chunk 的原文存这里，配合 FULLTEXT INDEX 做关键词检索
-- 和 Milvus 的关系：
--   Milvus 存向量（语义检索）
--   本表存原文（关键词检索）
--   chunk_id = doc{documentId}_{index}，两边用同一个 ID 关联
-- FULLTEXT INDEX + ngram 分词器：MySQL 8.0 原生支持中文全文检索，类似 ES 的倒排索引
-- =============================================

CREATE TABLE IF NOT EXISTS rag_document_chunk (
    id              BIGINT       PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID',
    document_id     BIGINT       NOT NULL                COMMENT '所属文档ID（关联 rag_document.id）',
    chunk_id        VARCHAR(50)  NOT NULL                COMMENT '分段唯一标识（doc{documentId}_{index}，和 Milvus 的 document ID 一致）',
    chunk_index     INT          NOT NULL                COMMENT '分段序号（第几个 chunk）',
    content         TEXT         NOT NULL                COMMENT '分段文本内容',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_document_id (document_id),
    FULLTEXT INDEX ft_content (content) WITH PARSER ngram  -- ngram 分词器支持中文
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='文档分段表（关键词检索）';

CREATE TABLE IF NOT EXISTS user_memory (
    id              BIGINT       PRIMARY KEY AUTO_INCREMENT COMMENT '主键ID（同时作为 Milvus document ID）',
    user_id         BIGINT       NOT NULL                COMMENT '用户ID（关联 sys_user）',
    content         VARCHAR(500) NOT NULL                COMMENT '记忆内容（一句话描述用户偏好或信息）',
    create_time     DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    INDEX idx_user_id (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户长期记忆表';
