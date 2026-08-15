package com.liwx.learning.agent.mcp;

import com.liwx.learning.agent.tool.RagTool;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
import org.springframework.stereotype.Component;

/**
 * RAG 知识库的 MCP Server 壳（对外暴露）
 *
 * 架构定位——「一个内核，两壳暴露」：
 *   RagTool（@Tool）        → 对内：Agent 的 Function Calling 直接调用（进程内方法调用，零协议开销）
 *   RagMcpTools（@McpTool） → 对外：通过 MCP 协议暴露给外部 AI 客户端（Claude Desktop / Cursor / 其他项目）
 *
 * 为什么单独一个类、而不是直接在 RagTool 上加 @McpTool：
 *   1. @Tool 和 @McpTool 是两套独立扫描体系（Function Calling 扫描器 / MCP annotation-scanner），
 *      混在一起 RagTool 会同时承担对内对外两个职责，职责不单一
 *   2. 对内对外的工具描述面向不同读者：对内描述给本项目的千问模型看，对外描述给完全不了解
 *      本项目的外部模型看，文案策略不同
 *   3. 检索内核（向量+关键词+Rerank 混合检索）全部在 RagTool，本类只做委托——
 *      将来改造检索逻辑只改一处，两边自动受益
 *
 * 为什么对内不走 MCP 回环：见 RagTool 注释——MCP 是跨进程互操作协议，
 * 自己调自己绕 MCP 等于方法调用硬转 HTTP+JSON-RPC，纯开销
 */
@Component
public class RagMcpTools {

    private final RagTool ragTool;

    public RagMcpTools(RagTool ragTool) {
        this.ragTool = ragTool;
    }

    /**
     * 对外暴露的知识库检索工具
     *
     * description 面向外部模型（它们对本项目一无所知），必须自描述：
     * 说明知识库属于哪个系统、能查到什么、什么时候该调用
     * 工具名用 snake_case（MCP 惯例），与对内的 searchKnowledge 区分开
     */
    @McpTool(name = "search_knowledge_base",
            description = "搜索 liwx_learning 项目的私有知识库（内部文档库，非公开互联网）。知识库包含该项目相关领域的文档内容，采用向量+关键词混合检索并经过重排序。当用户的问题可能涉及该项目私有资料、内部文档时调用此工具。普通常识问题不要调用。")
    public String searchKnowledgeBase(
            @McpToolParam(description = "用户的问题或搜索关键词", required = true) String query
    ) {
        // 委托给同一个检索内核：混合检索（Milvus 向量 + MySQL 全文索引）→ 合并去重 → Rerank 取 Top3
        return ragTool.searchKnowledge(query);
    }
}
