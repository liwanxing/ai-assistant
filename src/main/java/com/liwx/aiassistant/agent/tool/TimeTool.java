package com.liwx.aiassistant.agent.tool;

import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * 时间查询工具
 * 最简单的工具示例：模型看到 description 后，遇到"现在几点"之类的问题会自动调用
 */
@Slf4j
@Component
public class TimeTool {

    @Tool(description = "获取当前日期和时间。当用户询问现在几点、今天日期、当前时间等问题时调用此工具。")
    public String getCurrentTime() {
        log.info("TimeTool 被调用");
        return LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}
