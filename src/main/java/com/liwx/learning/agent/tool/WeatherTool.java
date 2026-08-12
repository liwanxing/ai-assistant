package com.liwx.learning.agent.tool;

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
 *
 * 模型看到 @Tool 的 description 后，遇到"今天天气怎么样""北京多少度"等问题会自动调用
 * 调用链：模型调用 → 城市名传给本工具 → 本工具请求高德 API → 返回实况天气文本给模型
 *
 * 高德 API 文档：https://lbs.amap.com/api/webservice/guide/api/weatherinfo
 * Key 在 application-local.yml 里配置（amap.api-key）
 */
@Slf4j
@Component
public class WeatherTool {

    @Value("${amap.api-key}")
    private String apiKey;

    private final RestClient restClient = RestClient.builder()
            .baseUrl("https://restapi.amap.com")
            .build();

    /**
     * 查询指定城市的实况天气
     *
     * @param city 城市名，如"北京""上海""深圳"
     * @return 天气信息文本（温度、天气、风向、湿度等），返回给模型让它组织语言回答用户
     */
    @Tool(description = "查询指定城市的实时天气。当用户问今天天气、温度多少、要不要带伞、穿什么衣服时调用此工具。")
    public String getWeather(
            @ToolParam(description = "城市名，如：北京、上海、深圳、广州") String city
    ) {
        log.info("WeatherTool 被调用，查询城市：{}", city);

        try {
            // 用 Map 接收 JSON（Spring Boot 4 用 Jackson 3，JsonNode 包路径变了，用 Map 避免兼容问题）
            @SuppressWarnings("unchecked")
            Map<String, Object> response = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .path("/v3/weather/weatherInfo")
                            .queryParam("key", apiKey)
                            .queryParam("city", city)
                            .queryParam("extensions", "base")   // base = 实况天气
                            .queryParam("output", "JSON")
                            .build())
                    .retrieve()
                    .body(Map.class);

            if (response == null) {
                return "天气查询失败：未收到响应";
            }

            // 高德 API 返回 status="1" 表示成功
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

            // 取第一个结果（实况天气只有一条）
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

        } catch (Exception e) {
            log.error("天气查询异常：{}", e.getMessage());
            return "天气查询服务暂时不可用，请稍后再试";
        }
    }
}
