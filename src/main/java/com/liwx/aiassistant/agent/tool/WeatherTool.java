package com.liwx.aiassistant.agent.tool;

import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

/**
 * 天气查询工具（高德天气 API）
 */
@Slf4j
@Component
public class WeatherTool {

    @Value("${amap.api-key}")
    private String apiKey;

    /** 高德 API 主机地址。共享 RestClient 不带 baseUrl（它同时服务多个工具），需在此拼完整 URL */
    @Value("${amap.base-url:https://restapi.amap.com}")
    private String baseUrl;

    private final RestClient restClient;

    public WeatherTool(RestClient restClient) {
        this.restClient = restClient;
    }

    @Tool(description = "查询指定城市的实时天气。当用户问今天天气、温度多少、要不要带伞、穿什么衣服时调用此工具。")
    @CircuitBreaker(name = "weatherCircuitBreaker", fallbackMethod = "fallback")
    public String getWeather(
            @ToolParam(description = "城市名，如：北京、上海、深圳、广州") String city
    ) {
        log.info("WeatherTool 被调用，查询城市：{}", city);

        // 模板变量方式拼 URL：中文城市名会自动 URL 编码
        @SuppressWarnings("unchecked")
        Map<String, Object> response = restClient.get()
                .uri(baseUrl + "/v3/weather/weatherInfo?key={key}&city={city}&extensions={extensions}&output={output}",
                        apiKey, city, "base", "JSON")
                .retrieve()
                .body(Map.class);

        if (response == null) {
            return "天气查询失败：未收到响应";
        }

        String status = String.valueOf(response.get("status"));
        if (!"1".equals(status)) {
            String info = String.valueOf(response.getOrDefault("info", "未知错误"));
            log.warn("天气查询失败：{}", info);
            return "天气查询失败：" + info;
        }

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> lives = (List<Map<String, Object>>) response.get("lives");
        if (lives == null || lives.isEmpty()) {
            return "未找到该城市的天气信息";
        }

        Map<String, Object> live = lives.get(0);
        String result = String.format(
                "城市：%s%s\n天气：%s\n温度：%s℃\n风向：%s风 %s\n湿度：%s%%\n更新时间：%s",
                live.getOrDefault("province", ""),
                live.getOrDefault("city", ""),
                live.getOrDefault("weather", ""),
                live.getOrDefault("temperature", ""),
                live.getOrDefault("winddirection", ""),
                live.getOrDefault("windpower", ""),
                live.getOrDefault("humidity", ""),
                live.getOrDefault("reporttime", "")
        );

        log.info("天气查询成功：{}{}", live.get("province"), live.get("city"));
        return result;
    }

    /** 熔断降级：高德 API 连续失败时返回兜底提示 */
    private String fallback(String city, Throwable t) {
        log.warn("WeatherTool 熔断降级，city={}，原因：{}", city, t.getMessage());
        return "天气查询服务暂时不可用，请稍后再试";
    }
}
