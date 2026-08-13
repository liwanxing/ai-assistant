package com.liwx.learning.agent.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * 经营分析工具（Graph 工作流）
 *
 * 通过 HTTP 调用 graph-learning-java 项目的 /graph/analyze 接口，
 * 让独立的 Graph 工作流引擎做多步骤经营分析（查数据 → 关联 → 分析 → 生成报告）。
 *
 * 调用链：用户问经营分析问题 → 模型调用本工具 → 本工具 HTTP 调 Graph 服务 → 返回分析报告
 *
 * 超时设 2 分钟：Graph 内部多次调 LLM，响应通常 20-30 秒，留足余量
 */
@Slf4j
@Component
public class GraphTool {

    // RestClient = Java 版的 fetch/axios（前端），本质就是发 HTTP 请求
    // Java 发 HTTP 的几种方式：
    //   RestClient（Spring 6.1+，同步，链式调用）← 当前用这个
    //   WebClient（响应式，异步流式）
    //   Feign（声明式，只写接口不写实现，Spring Cloud 微服务场景用得多）
    private final RestClient restClient;

    public GraphTool(@Value("${graph.service-url:http://localhost:8081}") String serviceUrl) {
        // 超时配置：底层就是给 HttpURLConnection 设 connectTimeout / readTimeout
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);     // 连接超时 5 秒
        factory.setReadTimeout(120000);      // 读取超时 2 分钟（Graph 内部多次 LLM 调用）
        this.restClient = RestClient.builder()
                .baseUrl(serviceUrl)
                .requestFactory(factory)
                .build();
    }

    @Tool(description = "执行复杂的经营分析任务，包括销售趋势、用户分层、商品销量、多表关联分析等。当用户需要深度数据分析、生成经营报告、对比不同维度的业务数据时调用此工具。")
    public String analyze(
            @ToolParam(description = "用户的完整分析需求，如：分析最近两个月的销售趋势、各品类销量对比、用户消费行为分析") String query
    ) {
        log.info("GraphTool 被调用，分析需求：{}", query);

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.post()
                    .uri("/graph/analyze")
                    .body(Map.of("query", query))
                    .retrieve()
                    .body(Map.class);

            if (response == null) {
                return "分析服务未返回结果，请稍后再试";
            }

            StringBuilder result = new StringBuilder();
            result.append((String) response.getOrDefault("report", "未生成报告"));

            // 附带质检评分和识别的维度
            Object score = response.get("quality_score");
            if (score != null) {
                result.append("\n\n（分析质检评分：").append(score).append("）");
            }

            @SuppressWarnings("unchecked")
            List<String> dimensions = (List<String>) response.get("dimensions");
            if (dimensions != null && !dimensions.isEmpty()) {
                result.append("\n分析维度：").append(String.join("、", dimensions));
            }

            log.info("GraphTool 分析完成，score={}", score);
            return result.toString();

        } catch (Exception e) {
            log.error("GraphTool 分析失败：{}", e.getMessage());
            return "经营分析服务暂时不可用，请稍后再试";
        }
    }
}
