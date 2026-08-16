package com.liwx.learning.rag.mq;

import com.liwx.learning.rag.enums.SplitStrategy;
import com.liwx.learning.rag.service.RagService;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.stereotype.Component;

/**
 * 文档处理消息生产者：上传接口调它发消息，消费端（DocumentProcessConsumer）异步处理
 *
 * 两级降级，保证"MQ 故障不拖垮上传"：
 * 1. 配置开关 rag.mq.enabled=false → 直接走 @Async（本地没起 MQ 容器时用）
 * 2. syncSend 抛异常（MQ 挂了 / 发送超时）→ 降级 @Async，本次上传照常成功
 *
 * 用 syncSend（同步等 Broker ACK）而不是 asyncSend：消息小、上传接口本就秒回，
 * 换"发送确认"的可靠性值得——发送失败当场降级，而不是 fire-and-forget 丢了都不知道
 */
@Slf4j
@Component
public class DocumentProcessProducer {

    private final RocketMQTemplate rocketMQTemplate;
    private final RagService ragService;

    @Value("${rag.mq.enabled:true}")
    private boolean mqEnabled;

    @Value("${rag.mq.topic:rag-document-process}")
    private String topic;

    public DocumentProcessProducer(RocketMQTemplate rocketMQTemplate, RagService ragService) {
        this.rocketMQTemplate = rocketMQTemplate;
        this.ragService = ragService;
    }

    /**
     * 发送文档处理消息；MQ 关闭或发送失败时降级为 @Async 处理
     */
    public void send(Long documentId, String filePath, SplitStrategy splitStrategy) {
        DocumentProcessMessage message = new DocumentProcessMessage(documentId, filePath, splitStrategy.name());

        if (!mqEnabled) {
            log.info("MQ 通道已关闭(rag.mq.enabled=false)，降级 @Async, documentId={}", documentId);
            ragService.processDocumentAsync(documentId, filePath, splitStrategy);
            return;
        }

        try {
            rocketMQTemplate.syncSend(topic, MessageBuilder.withPayload(message).build());
            log.info("文档处理消息已发送, documentId={}, topic={}", documentId, topic);
        } catch (Exception e) {
            log.warn("MQ 发送失败，降级 @Async, documentId={}, error={}", documentId, e.getMessage());
            ragService.processDocumentAsync(documentId, filePath, splitStrategy);
        }
    }
}
