package com.liwx.learning.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.RestClient;

/**
 * Langfuse 可观测性配置：本类只是链路的"工厂环节"，完整链路——
 *   yml 提供参数（key/url）→ 本类构建带鉴权头的 RestClient →
 *   AiConfig 把它装进 LangfuseAdvisor → Advisor 每次调用后用它向 Langfuse 服务上报 trace。
 * yml 管属性，本类管造对象（Basic Auth 头的拼接计算写不进 yml）；存储与展示在 Langfuse 服务端（PG 库）
 *
 * @ConditionalOnProperty：只有配置了 langfuse.enabled=true 时才创建这个 Bean
 * 没配 → Bean 不存在 → AiConfig 里 @Autowired(required=false) 拿到 null → 不注册 LangfuseAdvisor
 * 配了 → Bean 存在 → AiConfig 注册 LangfuseAdvisor，追踪每次 ChatClient 调用
 *
 * 好处：开发环境不需要启动 Langfuse 服务，也不需要配 API Key，不影响项目启动
 */
@Configuration
@ConditionalOnProperty(name = "spring.ai.langfuse.enabled", havingValue = "true")
public class LangfuseConfig {

    @Value("${spring.ai.langfuse.secret-key}")
    private String secretKey;

    @Value("${spring.ai.langfuse.public-key}")
    private String publicKey;

    @Value("${spring.ai.langfuse.url}")
    private String baseUrl;

    @Bean
    public RestClient langfuseRestClient() {
        return RestClient.builder()
                .baseUrl(baseUrl)
                .defaultHeaders(h -> {
                    String auth = java.util.Base64.getEncoder()
                            .encodeToString((publicKey + ":" + secretKey).getBytes());
                    h.set(HttpHeaders.AUTHORIZATION, "Basic " + auth);
                    h.set(HttpHeaders.CONTENT_TYPE, "application/json");
                })
                .build();
    }
}
