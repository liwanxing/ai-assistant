package com.liwx.learning;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
@MapperScan("com.liwx.learning.mapper")
public class LiwanxingLearningProjectsApplication {

	public static void main(String[] args) {
		SpringApplication.run(LiwanxingLearningProjectsApplication.class, args);
	}

}
