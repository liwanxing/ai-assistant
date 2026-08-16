package com.liwx.learning;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@SpringBootApplication
@EnableAsync  // @Async 异步支持：MQ 降级路径（RagService.processDocumentAsync）用，主路径已改走 RocketMQ
@EnableScheduling  // 开启 @Scheduled 定时任务支持（总闸）：历史数据清理等低峰期任务（不开这个注解，@Scheduled 不会生效）
@MapperScan("com.liwx.learning")  // 扫描所有模块的 mapper（user、ai、rag 三域的 mapper 包）
public class LiwanxingLearningProjectsApplication {

	public static void main(String[] args) {
		SpringApplication.run(LiwanxingLearningProjectsApplication.class, args);
	}

}
