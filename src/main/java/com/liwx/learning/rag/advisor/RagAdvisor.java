package com.liwx.learning.rag.advisor;

import com.liwx.learning.rag.service.RerankService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import reactor.core.publisher.Flux;

import java.util.ArrayList;
import java.util.List;

/**
 * RAG 知识检索 Advisor：向量检索 + Rerank 重排序，把检索到的知识拼到 SYSTEM 末尾
 *
 * 数据变化示例：
 *   输入：  SYSTEM: 你是一个知识库问答助手...
 *           USER:   请假怎么请？
 *   输出：  SYSTEM: 你是一个知识库问答助手...
 *                  参考资料：
 *                  员工请假需提前在OA系统提交申请，3天以内主管审批...
 *                  年假每年5天，需提前一周申请...
 *           USER:   请假怎么请？
 */
@Slf4j
public class RagAdvisor implements CallAdvisor, StreamAdvisor {

    private final VectorStore vectorStore;
    private final RerankService rerankService;

    private static final String CONTEXT_PREFIX = "\n\n参考资料：\n";

    public RagAdvisor(VectorStore vectorStore, RerankService rerankService) {
        this.vectorStore = vectorStore;
        this.rerankService = rerankService;
    }

    /**
     * 流式调用（SSE）：本项目 RAG 问答走的就是这条路径
     * 前置通知：检索知识库 → 拼参考资料到 SYSTEM → 交给下一个 Advisor
     */
    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        ChatClientRequest processed = injectContext(request);
        return chain.nextStream(processed);
    }

    /**
     * 同步调用：逻辑和流式一样
     */
    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        ChatClientRequest processed = injectContext(request);
        return chain.nextCall(processed);
    }

    /**
     * 知识检索核心逻辑：
     * 1. 取用户最后一句话作为检索 query
     * 2. 向量检索 topK=10（多召回给 Rerank 留筛选空间）
     * 3. Rerank 重排序取最相关的 3 条
     * 4. 带编号拼接成参考资料，追加到 SYSTEM 消息末尾
     */
    private ChatClientRequest injectContext(ChatClientRequest request) {
        String question = getLastUserMessage(request);
        if (question == null) {
            return request;
        }

        try {
            // 1. 向量检索：从 Milvus 多召回候选
            List<Document> documents = vectorStore.similaritySearch(SearchRequest.builder()
                    .query(question)
                    .topK(10)
                    .build());

            // 2. Rerank 重排序：用专门的排序模型重新打分，取最相关的 3 条
            if (!documents.isEmpty()) {
                documents = rerankService.rerank(question, documents, 3);
            }

            if (documents.isEmpty()) {
                return request;
            }

            // 3. 带编号拼接，方便大模型引用时标注来源
            StringBuilder contextBuilder = new StringBuilder(CONTEXT_PREFIX);
            for (int i = 0; i < documents.size(); i++) {
                contextBuilder.append("【参考资料").append(i + 1).append("】\n")
                        .append(documents.get(i).getText())
                        .append("\n\n");
            }

            // 4. 追加到 SYSTEM 消息末尾
            List<Message> instructions = request.prompt().getInstructions();
            List<Message> newInstructions = new ArrayList<>();
            for (Message msg : instructions) {
                if (msg.getMessageType() == MessageType.SYSTEM) {
                    newInstructions.add(new SystemMessage(msg.getText() + contextBuilder));
                } else {
                    newInstructions.add(msg);
                }
            }

            return request.mutate()
                    .prompt(Prompt.builder().messages(newInstructions).build())
                    .build();
        } catch (Exception e) {
            log.warn("RAG 检索失败（降级为无参考资料）：error={}", e.getMessage());
            return request;
        }
    }

    /** 取 prompt 中最后一条 USER 消息的文本 */
    private String getLastUserMessage(ChatClientRequest request) {
        List<Message> instructions = request.prompt().getInstructions();
        for (int i = instructions.size() - 1; i >= 0; i--) {
            Message msg = instructions.get(i);
            if (msg.getMessageType() == MessageType.USER) {
                return msg.getText();
            }
        }
        return null;
    }

    @Override
    public String getName() {
        return "RagAdvisor";
    }

    @Override
    public int getOrder() {
        return 80;
    }
}
