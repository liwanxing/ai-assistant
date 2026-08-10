package com.liwx.learning;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@SpringBootApplication
@EnableAsync  // 开启 @Async 异步方法支持，RagService 的文档处理用异步线程执行，不阻塞用户请求
@MapperScan("com.liwx.learning")  // 扫描所有模块的 mapper（user.mapper、rag.mapper 等）
public class LiwanxingLearningProjectsApplication {

	public static void main(String[] args) {
		SpringApplication.run(LiwanxingLearningProjectsApplication.class, args);
	}

}
