package com.liwx.aiassistant.rag.enums;

/**
 * 文档切分策略
 * token：按 token 数量硬切，速度最快，但可能切断句子（如"审批流|程是"）
 * paragraph：以换行为天然边界，保持每个 chunk 语义完整，适合规章制度、合同条款等有明确段落结构的文档
 * semantic：通过 embedding 相似度自动识别话题边界，切分效果最好，代价是多耗 API 调用
 */
public enum SplitStrategy {

    TOKEN,
    PARAGRAPH,
    SEMANTIC
}
