package com.liwx.learning.rag.task;

import com.liwx.learning.rag.entity.RagDocument;
import com.liwx.learning.rag.service.MailAlertService;
import com.liwx.learning.rag.service.RagService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 文档处理超时对账任务（薄触发器，逻辑在 RagService.failStuckProcessing）
 *
 * 与 HistoryCleanupTask 同一套 Task/Service 分层：本类只管"何时触发 + 发通知"，
 * 扫描判死逻辑在 Service——将来换调度方式（XXL-Job / 手动触发）直接调同一个方法
 *
 * 定时任务的告警形态：扫到卡死文档 → 标 FAILED（状态闭环）→ 汇总一封邮件（人能感知）。
 * 汇总而不是一条一封：一次故障往往卡死一批，逐条发会把收件箱打成垃圾场
 *
 * cron 说明（Quartz 6 域）：0 30 * * * ? = 每小时第 30 分钟。
 * 对账类任务不需要高频（卡死 6 小时才判死，晚 59 分钟发现毫无影响），一小时一轮足够
 *
 * @Scheduled 单线程边界（同 HistoryCleanupTask 注释）：与凌晨清理任务共享调度线程，
 * 本任务只做一次索引查询 + 少量 update + 一封邮件，秒级完成，不会挤占清理任务的窗口
 */
@Slf4j
@Component
public class DocumentTimeoutTask {

    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final RagService ragService;
    private final MailAlertService mailAlertService;
    private final boolean enabled;
    private final int timeoutHours;

    public DocumentTimeoutTask(RagService ragService,
                               MailAlertService mailAlertService,
                               @Value("${rag.document-timeout.enabled:true}") boolean enabled,
                               @Value("${rag.document-timeout.timeout-hours:6}") int timeoutHours) {
        this.ragService = ragService;
        this.mailAlertService = mailAlertService;
        this.enabled = enabled;
        this.timeoutHours = timeoutHours;
    }

    @Scheduled(cron = "${rag.document-timeout.cron:0 30 * * * ?}")
    public void failStuckDocuments() {
        // 开关放方法内（同 HistoryCleanupTask 的写法与理由）：类始终是 Bean，改配置重启即生效
        if (!enabled) {
            return;
        }
        try {
            LocalDateTime before = LocalDateTime.now().minusHours(timeoutHours);
            List<RagDocument> stuck = ragService.failStuckProcessing(before);
            if (stuck.isEmpty()) {
                return;   // 一切正常：静默返回，不刷日志（每小时一条"无事发生"是噪音）
            }
            mailAlertService.send(buildSubject(stuck), buildContent(stuck));
        } catch (Exception e) {
            // @Scheduled 会吞异常只在调试日志留痕，主动打 error 保证可见（同 HistoryCleanupTask）
            log.error("文档超时对账任务异常：{}", e.getMessage(), e);
        }
    }

    private String buildSubject(List<RagDocument> stuck) {
        return stuck.size() + " 个文档处理超时已标 FAILED";
    }

    private String buildContent(List<RagDocument> stuck) {
        StringBuilder sb = new StringBuilder();
        sb.append("以下文档卡在 PROCESSING 超过 ").append(timeoutHours)
                .append(" 小时，已判定任务无人认领（消息进死信 / 任务丢失 / 服务中断），统一标记为 FAILED：\n\n");
        for (RagDocument doc : stuck) {
            sb.append("- 文档[").append(doc.getId()).append("] ").append(doc.getFileName())
                    .append("，停滞于 ")
                    .append(doc.getUpdateTime() != null ? doc.getUpdateTime().format(FMT) : "未知")
                    .append('\n');
        }
        sb.append("\n处理建议：检查 RocketMQ 死信队列（%DLQ%rag-doc-group）与服务日志，确认原因后可重新上传文档。");
        return sb.toString();
    }
}
