package com.liwx.learning.config;

import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.util.Timeout;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * RestClient 连接池配置
 *
 * 为什么需要连接池：
 *   默认的 SimpleClientHttpRequestFactory 每次请求都新建 TCP 连接 → 三次握手 → 请求 → 四次挥手
 *   高并发下大量 TIME_WAIT 端口，性能差
 *   连接池预建 TCP 连接，请求来了直接复用，省掉握手开销
 *
 * 配置参数（参考 Apache HttpClient 5 默认值 + 实际调用量）：
 *   maxTotal=200          最多 200 个 TCP 连接同时存活
 *   defaultMaxPerRoute=20 每个目标地址最多 20 个并发连接
 *   connectTimeout=5s     建立 TCP 连接的超时时间
 *   connectionTimeToLive=5min  单个连接最长存活时间，防止长期持有过期连接
 */
@Configuration
public class RestClientConfig {

    /**
     * 连接池：共享给所有 RestClient 使用
     * WeatherTool 用默认超时，ResearchTool 用 5 分钟超时，都复用同一个连接池
     */
    @Bean(destroyMethod = "close")
    public PoolingHttpClientConnectionManager connectionManager() {
        PoolingHttpClientConnectionManager manager = new PoolingHttpClientConnectionManager();
        manager.setMaxTotal(200);
        manager.setDefaultMaxPerRoute(20);
        return manager;
    }

    /**
     * 全局共享的 RestClient Bean，带连接池
     * WeatherTool 等注入这个 Bean，不再各自创建
     */
    @Bean
    public RestClient restClient(PoolingHttpClientConnectionManager connectionManager) {
        return RestClient.builder()
                .requestFactory(new org.springframework.http.client.HttpComponentsClientHttpRequestFactory(
                        HttpClients.custom()
                                .setConnectionManager(connectionManager)
                                .setDefaultRequestConfig(org.apache.hc.client5.http.config.RequestConfig.custom()
                                        .setConnectTimeout(Timeout.ofSeconds(5))
                                        .setConnectionRequestTimeout(Timeout.ofSeconds(3))
                                        .build())
                                .build()))
                .build();
    }
}
