package com.liwx.learning;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 冒烟测试：方法体虽然是空的，但 @SpringBootTest 会启动整个 Spring 容器。
 * 能启动成功 = 所有 Bean 的创建、依赖注入、配置绑定都 OK。
 * 启动失败 = 某处装配出了问题（缺配置、依赖找不到、提示词文件丢失等）。
 *
 * mvn test    自动批量跑所有测试类（不用一个个手动点），默认跳过 @Tag("integration")
 * mvn test -Dgroups=integration  只跑集成测试（需本地启动好 MySQL/Redis/Milvus）
 */
@SpringBootTest
@Tag("integration")
class LiwanxingLearningProjectsApplicationTests {

	@Test
	void contextLoads() {
	}

}
