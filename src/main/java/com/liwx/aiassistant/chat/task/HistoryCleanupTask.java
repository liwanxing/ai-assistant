package com.liwx.aiassistant.chat.task;

import com.liwx.aiassistant.chat.service.SessionCleanupService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 历史数据定时清理任务（薄触发器）
 *
 * 职责分离：本类只负责「何时触发」，清理逻辑全在 SessionCleanupService——
 * 将来换调度方式（XXL-Job 的 @XxlJob、手动管理接口触发）时，
 * 直接调同一个 service.cleanExpired() 即可，业务逻辑零改动
 *
 * cron 说明（Quartz 6 域：秒 分 时 日 月 周）：0 0 3 * * ? = 每天凌晨 3 点（低峰期）
 *
 * @Scheduled 的三个已知边界（面试考点）：
 *   1. 错过不补跑：凌晨 3 点应用恰好在宕机，本轮跳过，第二天自然再清（无 misfire 补偿）
 *   2. 默认单线程：所有 @Scheduled 任务共享 1 个调度线程，任务多了互相阻塞
 *      （可通过 spring.task.scheduling.pool.size 配线程池；本项目任务少，不配）
 *   3. 多实例重复执行：@Scheduled 是进程内的，部署多实例会每个实例都触发一次——
 *      清理逻辑幂等删不出错，但浪费；届时上 ShedLock 分布式锁或换 XXL-Job
 */
@Slf4j
@Component
public class HistoryCleanupTask {

    private final SessionCleanupService cleanupService;
    private final boolean enabled;

    public HistoryCleanupTask(SessionCleanupService cleanupService,
                              @Value("${rag.cleanup.enabled:true}") boolean enabled) {
        this.cleanupService = cleanupService;
        this.enabled = enabled;
    }

    @Scheduled(cron = "${rag.cleanup.cron:0 0 3 * * ?}")
    public void cleanExpiredSessions() {
        // 开关放方法内而不是 @ConditionalOnProperty：类始终注册为 Bean，
        // 配置改了重启即生效，且语义直白（本地开发不想删数据就设 rag.cleanup.enabled=false）
        if (!enabled) {
            return;
        }
        long start = System.currentTimeMillis();
        log.info("历史数据定时清理任务开始（保留期见 rag.cleanup.retain-days）");
        try {
            cleanupService.cleanExpired();
        } catch (Exception e) {
            // @Scheduled 的异常会被调度器吞掉只在日志留痕，这里主动打 error 级别保证可见
            log.error("历史数据定时清理任务异常终止：{}", e.getMessage(), e);
        }
        log.info("历史数据定时清理任务结束，耗时 {} ms", System.currentTimeMillis() - start);
    }
}
