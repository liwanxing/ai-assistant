package com.liwx.learning.agent.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * 深度调研工具（Python Agent）
 *
 * 通过 HTTP 调用 Python LangGraph Agent 的 /research 接口，做多步骤深度调研（搜索 → 阅读 → 总结 → 生成报告）。
 * HTTP 是语言无关的协议：Python 用 FastAPI 暴露接口，Java 用 RestClient 发请求，两边只约定 URL + JSON 格式。
 *
 * 调用链：用户问调研类问题 → 模型调本工具 → 本工具 HTTP 调 Python Agent → 返回调研报告
 *
 * 超时设 5 分钟：深度调研内部多次搜索 + LLM 调用，耗时较长
 */
@Slf4j
@Component
public class ResearchTool {

    private final RestClient restClient;

    public ResearchTool(@Value("${research.service-url:http://localhost:8000}") String serviceUrl) {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);       // 连接超时 5 秒
        factory.setReadTimeout(300000);        // 读取超时 5 分钟（深度调研多次搜索+LLM调用）
        this.restClient = RestClient.builder()
                .baseUrl(serviceUrl)
                .requestFactory(factory)
                .build();
    }

    @Tool(description = "执行深度调研任务，对某个主题进行多步骤搜索、分析和总结。当用户需要深入调研某个技术主题、行业趋势、方案对比等需要联网搜索和综合分析的任务时调用此工具。")
    public String research(
            @ToolParam(description = "调研需求描述，如：调研主流的Java AI框架、对比Spring AI和LangChain4j") String query
    ) {
        log.info("ResearchTool 被调用，调研需求：{}", query);

        try {
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

        } catch (Exception e) {
            log.error("ResearchTool 调研失败：{}", e.getMessage());
            return "深度调研服务暂时不可用，请稍后再试";
        }
    }
}
