package com.liwx.aiassistant.rag.mq;

import com.liwx.aiassistant.rag.enums.SplitStrategy;
import com.liwx.aiassistant.rag.exception.DocumentParseException;
import com.liwx.aiassistant.rag.service.RagService;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * 文档处理消息消费者：收到消息调 RagService 单次处理
 *
 * 重试与死信（可靠性核心，注意与 @Async 应用层重试是两套机制）：
 * - onMessage 抛异常 → 消费失败，Broker 按延迟级别自动重投（默认最多 16 次，间隔递增）
 * - 16 次耗尽 → 消息进死信 Topic（%DLQ%消费者组），RocketMQ Dashboard 可查、可人工重发
 * - 消费端不吞【暂时性】异常：catch 了等于告诉 MQ "消费成功"，Broker 的重投机制就废了；
 *   唯一例外是 DocumentParseException（文件损坏/加密/不存在，重试也不会好）：
 *   有意吞掉并当场标 FAILED——毒消息 1 秒出结果，而不是空转 16 次重投（约 5 小时）才进死信
 *
 * 幂等：至少一次投递意味着可能重复消费，由 RagService 的幂等清理兜住（先删旧 chunk 再写）
 *
 * topic / consumerGroup 用 ${} 占位符从 yml 读：rocketmq-spring 支持注解属性占位符，
 * 配置集中在 application.yml（rag.mq.*），改 Topic 不用动代码
 *
 * 开关联动：@ConditionalOnProperty 跟随 rag.mq.enabled（与 Producer 的降级开关同源）。
 * 没有它，设 false 时只是生产端不发消息，消费容器照样启动、连不上 NameServer 无限重连刷日志——
 * 开关只关一半。matchIfMissing=true：配置缺失时默认开，与 yml 里 ${RAG_MQ_ENABLED:true} 的默认值一致
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "rag.mq.enabled", havingValue = "true", matchIfMissing = true)
@RocketMQMessageListener(
        topic = "${rag.mq.topic}",
        consumerGroup = "${rag.mq.consumer-group}"
)
public class DocumentProcessConsumer implements RocketMQListener<DocumentProcessMessage> {

    private final RagService ragService;

    public DocumentProcessConsumer(RagService ragService) {
        this.ragService = ragService;
    }

    @Override
    public void onMessage(DocumentProcessMessage message) {
        log.info("收到文档处理消息, documentId={}, splitStrategy={}",
                message.documentId(), message.splitStrategy());
        try {
            ragService.processDocumentOnce(
                    message.documentId(),
                    message.filePath(),
                    SplitStrategy.valueOf(message.splitStrategy()));
        } catch (DocumentParseException e) {
            // 确定性失败（文件坏了）：当场标 FAILED 后正常返回——正常返回 = ACK = 消费成功，
            // 不重投、不进死信。不这么做的话毒消息要空转 16 次重投（约 5 小时），文档一直转圈
            log.error("文档解析失败（不可恢复），跳过重试直接标 FAILED, documentId={}, error={}",
                    message.documentId(), e.getMessage());
            ragService.markFailed(message.documentId(), e.getMessage());
        } catch (Exception e) {
            // 暂时性失败：包装后照样上抛（不是吞异常）：onMessage 签名不带 throws 只能抛 RuntimeException；
            // 异常逃逸出本方法 = 告诉 Broker 消费失败 → 重投 → 耗尽进死信（类注释的可靠性链路）
            throw new RuntimeException("文档处理失败: documentId=" + message.documentId(), e);
        }
    }
}
