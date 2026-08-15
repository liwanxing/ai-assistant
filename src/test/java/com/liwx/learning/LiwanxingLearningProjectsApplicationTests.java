package com.liwx.learning;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 项目启动上下文测试：验证 Spring 容器能正常加载所有 Bean
 * 如果这个测试通过，说明所有配置、依赖、Bean 都没问题。
 * 如果失败，通常是某个自动配置缺少必要参数或依赖。
 */
@SpringBootTest
@Tag("integration")  // 集成测试：加载完整上下文需 MySQL/Milvus/Redis/API Key，mvn test 默认排除，手动跑：mvn test -Dgroups=integration
class LiwanxingLearningProjectsApplicationTests {

	@Test
	void contextLoads() {
	}

}
