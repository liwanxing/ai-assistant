package com.liwx.learning.ai.advisor;

import com.liwx.learning.ai.advisor.core.SemanticCacheStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import java.util.List;

/**
 * 语义缓存 Advisor（Semantic Cache）：命中缓存时短路 LLM 调用
 *
 * 数据变化示例：
 *   第一次问"请假怎么请" → 未命中 → 正常走 LLM（检索+生成）→ 答案异步写入 Milvus
 *   再问"请假流程是什么" → 语义相似度 0.97 > 0.95 → 直接返回缓存答案（毫秒级，0 token）
 *
 * 链路位置（ getOrder 最大 = 最内层，紧贴 LLM）：
 *   UserMemory → ChatMemory → Summary → Logger → 【本 Advisor】 → LLM
 *   为什么放最内层：命中短路时只跳过 LLM 调用本身——
 *   MessageChatMemoryAdvisor 照常把问答写入对话历史（否则命中后这句话在多轮里凭空消失）、
 *   外层 LangfuseAdvisor 照常执行（token 账本不重复计，见下方命中分支注释）
 *
 * 只实现 CallAdvisor 不实现 StreamAdvisor：
 *   主链路是 .call() + Controller 层 toSseFlux 假流（DashScope 流式工具调用 bug 的绕行方案），
 *   流式调用不经过本 Advisor，自然不缓存也不命中（无风险，只是少一层加速）
 */
@Slf4j
public class SemanticCacheAdvisor implements CallAdvisor {

    private final SemanticCacheStore cacheStore;

    /**
     * 动态问题防护词表：命中这些词的问题不读不写缓存。
     * 语义缓存最经典的坑——"现在几点了"被缓存后，明天问到返回的还是昨天的时间。
     * 对应本项目工具：TimeTool（现在/几点）、WeatherTool（今天/天气）、UserQueryTool（我的）
     */
    private static final List<String> SKIP_WORDS = List.of(
            "现在", "今天", "昨天", "明天", "当前", "此刻", "最新", "最近",
            "几点", "几号", "星期", "日期", "天气", "新闻", "我的");

    public SemanticCacheAdvisor(SemanticCacheStore cacheStore) {
        this.cacheStore = cacheStore;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        String question = extractCacheableQuestion(request);

        // 不可缓存（开关关闭/无用户消息/多模态/敏感词）→ 直通，零额外开销
        if (question == null) {
            return chain.nextCall(request);
        }

        // BEFORE：查缓存
        String cachedAnswer = cacheStore.lookup(question);
        if (cachedAnswer != null) {
            // 短路：不调 chain.nextCall，LLM 完全不执行，本次 token 消耗 = 0。
            // 响应里没有 Usage 元数据 → 外层 LangfuseAdvisor 不会上报 generation 事件也不打 token 行，
            // 账本干净：日志里“语义缓存命中”与“Token 用量”行互斥，统计不会重复计账。
            // 节省量是对比值：命中次数 × 同类问题不走缓存时的平均消耗（看首次调用的 Token 日志）
            log.info("语义缓存命中，跳过 LLM 调用：{}", question);
            ChatResponse chatResponse = new ChatResponse(List.of(
                    new Generation(new AssistantMessage(cachedAnswer))));
            // ChatClientResponse 是 record：用 builder 构造（2.0 没有 from 静态方法），
            // 透传原请求的 context（含会话 ID 等），外层 Advisor 感知不到这是缓存响应
            return ChatClientResponse.builder()
                    .chatResponse(chatResponse)
                    .context(request.context())
                    .build();
        }

        // MISS：正常调用，拿到答案后异步写入缓存
        ChatClientResponse response = chain.nextCall(request);
        String answer = extractAnswer(response);
        if (answer != null) {
            cacheStore.saveAsync(question, answer);
        }
        return response;
    }

    /**
     * 提取可缓存的问题：最后一条 USER 消息的文本
     * 返回 null 表示这个问题不适合走缓存
     */
    private String extractCacheableQuestion(ChatClientRequest request) {
        if (!cacheStore.isEnabled()) {
            return null;
        }
        List<Message> instructions = request.prompt().getInstructions();
        for (int i = instructions.size() - 1; i >= 0; i--) {
            Message msg = instructions.get(i);
            if (msg.getMessageType() != MessageType.USER) {
                continue;
            }
            // 多模态消息（带图片）不缓存：缓存 key 只是文本，答案却依赖图片内容，复用必错
            if (msg instanceof UserMessage um && um.getMedia() != null && !um.getMedia().isEmpty()) {
                return null;
            }
            String text = msg.getText();
            if (text == null || text.isBlank()) {
                return null;
            }
            // 过短问题不缓存："它呢""然后呢""为什么"这类代词/省略式追问，答案完全依赖上文，
            // 而缓存 key 只有 query 本身——自包含的知识型问题基本不会短于这个长度
            if (text.length() < 8) {
                return null;
            }
            // 动态问题防护（见 SKIP_WORDS 注释）
            for (String word : SKIP_WORDS) {
                if (text.contains(word)) {
                    return null;
                }
            }
            return text;
        }
        return null;
    }

    /** 从响应提取答案文本 */
    private String extractAnswer(ChatClientResponse response) {
        try {
            if (response == null || response.chatResponse() == null
                    || response.chatResponse().getResult() == null) {
                return null;
            }
            return response.chatResponse().getResult().getOutput().getText();
        } catch (Exception e) {
            return null;
        }
    }

    @Override
    public String getName() {
        return "SemanticCacheAdvisor";
    }

    @Override
    public int getOrder() {
        // 最大 order = 最内层：所有记忆/监控 Advisor 都在本 Advisor 外层，短路时它们照常工作
        return 1000;
    }
}
