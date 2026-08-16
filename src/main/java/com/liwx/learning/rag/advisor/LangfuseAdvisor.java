package com.liwx.learning.rag.advisor;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisor;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisor;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.web.client.RestClient;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
public class LangfuseAdvisor implements CallAdvisor, StreamAdvisor {

    private final RestClient restClient;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public LangfuseAdvisor(RestClient restClient) {
        this.restClient = restClient;
    }

    @Override
    public ChatClientResponse adviseCall(ChatClientRequest request, CallAdvisorChain chain) {
        String traceId = UUID.randomUUID().toString();
        String userId = getUserIdFromContext(request);
        String sessionId = getSessionIdFromContext(request);
        String userText = getUserText(request);

        ChatClientResponse response = chain.nextCall(request);

        List<Map<String, Object>> batch = new ArrayList<>();
        // 1. Trace
        Map<String, Object> traceBody = new HashMap<>();
        traceBody.put("id", traceId);
        traceBody.put("name", "chat-client");
        traceBody.put("timestamp", Instant.now().toString());
        if (userId != null) traceBody.put("userId", userId);
        if (sessionId != null) traceBody.put("sessionId", sessionId);
        traceBody.put("input", userText);

        Map<String, Object> traceEvent = new HashMap<>();
        traceEvent.put("id", UUID.randomUUID().toString());
        traceEvent.put("type", "trace-create");
        traceEvent.put("timestamp", Instant.now().toString());
        traceEvent.put("body", traceBody);
        batch.add(traceEvent);

        // 2. Generation (with input/output/usage/model)
        if (response != null && response.chatResponse() != null
                && response.chatResponse().getMetadata() != null
                && response.chatResponse().getMetadata().getUsage() != null) {
            var usage = response.chatResponse().getMetadata().getUsage();
            String modelName = response.chatResponse().getMetadata().getModel();
            String output = response.chatResponse().getResult().getOutput().getText();

            Map<String, Object> usageMap = new HashMap<>();
            usageMap.put("input", usage.getPromptTokens());
            usageMap.put("output", usage.getCompletionTokens());
            usageMap.put("total", usage.getTotalTokens());

            Map<String, Object> genBody = new HashMap<>();
            genBody.put("id", UUID.randomUUID().toString());
            genBody.put("traceId", traceId);
            genBody.put("name", "chat-generation");
            genBody.put("model", modelName);
            genBody.put("startTime", Instant.now().toString());
            genBody.put("endTime", Instant.now().toString());
            genBody.put("input", userText);
            genBody.put("output", output);
            genBody.put("usage", usageMap);

            Map<String, Object> genEvent = new HashMap<>();
            genEvent.put("id", UUID.randomUUID().toString());
            genEvent.put("type", "generation-create");
            genEvent.put("timestamp", Instant.now().toString());
            genEvent.put("body", genBody);
            batch.add(genEvent);

            log.info("Langfuse trace: model={}, tokens={}", modelName, usage.getTotalTokens());
        }

        // Send batch
        sendAsync(batch, "chat");

        return response;
    }

    @Override
    public Flux<ChatClientResponse> adviseStream(ChatClientRequest request, StreamAdvisorChain chain) {
        String traceId = UUID.randomUUID().toString();
        String userId = getUserIdFromContext(request);
        String sessionId = getSessionIdFromContext(request);
        String userText = getUserText(request);

        List<Map<String, Object>> batch = new ArrayList<>();

        Map<String, Object> traceBody = new HashMap<>();
        traceBody.put("id", traceId);
        traceBody.put("name", "chat-client-stream");
        traceBody.put("timestamp", Instant.now().toString());
        if (userId != null) traceBody.put("userId", userId);
        if (sessionId != null) traceBody.put("sessionId", sessionId);
        traceBody.put("input", userText);

        Map<String, Object> traceEvent = new HashMap<>();
        traceEvent.put("id", UUID.randomUUID().toString());
        traceEvent.put("type", "trace-create");
        traceEvent.put("timestamp", Instant.now().toString());
        traceEvent.put("body", traceBody);
        batch.add(traceEvent);

        sendAsync(batch, "stream");

        return chain.nextStream(request);
    }

    /**
     * 异步上报：观测数据不能拖慢用户响应——LLM 调用完在主线程拼好事件，
     * HTTP 发送交给虚拟线程后台执行（与 SemanticCacheStore.saveAsync 同一手法），
     * Langfuse 挂了/慢了也只是丢观测不伤主链路
     */
    private void sendAsync(List<Map<String, Object>> batch, String source) {
        Thread.ofVirtual().name("langfuse-reporter").start(() -> {
            try {
                Map<String, Object> batchPayload = new HashMap<>();
                batchPayload.put("batch", batch);
                restClient.post().uri("/api/public/ingestion")
                        .body(objectMapper.writeValueAsString(batchPayload))
                        .retrieve().toBodilessEntity();
            } catch (Exception e) {
                log.warn("Langfuse batch send failed ({}): {}", source, e.getMessage());
            }
        });
    }

    private String getUserText(ChatClientRequest request) {
        var userMessage = request.prompt().getUserMessage();
        return userMessage != null ? userMessage.getText() : "";
    }

    private String getUserIdFromContext(ChatClientRequest request) {
        Object value = request.context().get("user_id");
        return value != null ? value.toString() : null;
    }

    private String getSessionIdFromContext(ChatClientRequest request) {
        Object value = request.context().get("chat_memory_conversation_id");
        return value != null ? value.toString() : null;
    }

    @Override
    public String getName() {
        return "LangfuseAdvisor";
    }

    @Override
    public int getOrder() {
        return 100;
    }
}
