package com.liwx.learning.agent.tool;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.util.Timeout;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;

/**
 * 通过 HTTP 调用 Python LangGraph Agent 的 /research 接口，做多步骤深度调研。
 *
 * 熔断保护（Resilience4j）：
 *   Python Agent 是外部服务，可能宕机/超时/网络抖动。
 *   没有熔断时：每个请求都傻等 5 分钟 → Tomcat 线程耗尽 → 整个应用卡死（级联故障）。
 *   有熔断后：连续失败 5 次 → 自动熔断 30 秒 → 期间直接返回降级响应 → 30 秒后自动试探恢复。
 *   这就是"快速失败"（Fail Fast）：与其让 100 个请求都等 5 分钟，不如第 6 个开始直接告诉用户"服务暂时不可用"。
 */
@Slf4j
@Component
public class ResearchTool {

    private final RestClient restClient;

    /**
     * 为什么不用 MCP：
     * graph-learning-java（已经通过 MCP Client 调用）,这里专门不用作为学习体验差异，如果不用mcp需要自己写连接池（mcp会自带）提高性能
     *
     */
    public ResearchTool(@Value("${research.service-url:http://localhost:8000}") String serviceUrl) {
        //独立连接池：调研请求单次耗时可达 5 分钟，如果复用全局连接池，可能导致其他服务拿不到连接。
        PoolingHttpClientConnectionManager pool = new PoolingHttpClientConnectionManager();
        pool.setMaxTotal(10);
        pool.setDefaultMaxPerRoute(5);

        this.restClient = RestClient.builder()
                .baseUrl(serviceUrl)
                .requestFactory(new HttpComponentsClientHttpRequestFactory(
                        HttpClients.custom()
                                .setConnectionManager(pool)
                                .setDefaultRequestConfig(org.apache.hc.client5.http.config.RequestConfig.custom()
                                        .setConnectTimeout(Timeout.ofSeconds(5))
                                        .setResponseTimeout(Timeout.ofMinutes(5))
                                        .build())
                                .build()))
                .build();
    }

    /**
     * 执行深度调研
     * @CircuitBreaker 注解：自动熔断保护，name 必须和 application.yml 里的 resilience4j 配置一致
     * fallbackMethod：熔断/异常时的降级方法，返回兜底提示而不是白等超时
     */
    @Tool(description = "执行深度调研任务，对某个主题进行多步骤搜索、分析和总结。当用户需要深入调研某个技术主题、行业趋势、方案对比等需要联网搜索和综合分析的任务时调用此工具。")
    @CircuitBreaker(name = "researchCircuitBreaker", fallbackMethod = "fallback")
    public String research(
            @ToolParam(description = "调研需求描述，如：调研主流的Java AI框架、对比Spring AI和LangChain4j") String query
    ) {
        log.info("ResearchTool 被调用，调研需求：{}", query);

        String report = restClient.post()
                .uri("/research")
                .body(Map.of("query", query))
                .retrieve()
                .body(String.class);

        if (report == null || report.isBlank()) {
            return "调研服务未返回结果，请稍后再试";
        }

        log.info("ResearchTool 调研完成，报告长度：{}", report.length());
        return report;
    }

    /**
     * 降级方法：熔断触发或调用异常时自动执行
     * 方法签名必须和原方法一致，末尾加 Throwable 参数
     */
    private String fallback(String query, Throwable t) {
        log.warn("ResearchTool 熔断降级，query={}，原因：{}", query, t.getMessage());
        return "深度调研服务暂时不可用（" + t.getClass().getSimpleName() + "），请稍后再试或直接提问，我会基于已有知识尽力回答。";
    }
}
