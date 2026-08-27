package com.liwx.aiassistant.agent.tool;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.ai.tool.metadata.ToolMetadata;

/**
 * MCP 远程工具的熔断包装器
 *
 * 问题：MCP 工具是框架动态发现的远程 ToolCallback，不是我们的方法，没法加 @CircuitBreaker 注解
 * 解决：包一层——实现同一个接口，持有原对象，call() 时套熔断器，其他方法直接委托
 *
 * 本质是"套一层加逻辑"——装饰器、代理、AOP 都是同一回事：
 *   装饰器：强调"加功能"（原对象外面传进来，套一层增强）
 *   代理：强调"控制访问"（决定能不能调原对象）
 *   AOP：框架自动帮你套（@CircuitBreaker 注解底层就是 Spring 动态代理）
 *   这里 MCP 没法用注解，所以手写一个"静态代理"
 *
 * 框架怎么知道调我而不是原对象？
 *   AiConfig 里把原对象包一层，注册给 ChatClient 的是包装后的：
 *   wrappedCallbacks = map(tc -> new CircuitBreakerToolCallback(tc, mcpCircuitBreaker))
 *   builder.defaultTools(ToolCallbackProvider.from(wrappedCallbacks))
 *
 */
@Slf4j
public class CircuitBreakerToolCallback implements ToolCallback {

    private final ToolCallback delegate;
    private final CircuitBreaker circuitBreaker;

    public CircuitBreakerToolCallback(ToolCallback delegate, CircuitBreaker circuitBreaker) {
        this.delegate = delegate;
        this.circuitBreaker = circuitBreaker;
    }

    @Override
    public ToolDefinition getToolDefinition() {
        return delegate.getToolDefinition();
    }

    @Override
    public ToolMetadata getToolMetadata() {
        return delegate.getToolMetadata();
    }

    @Override
    public String call(String toolInput) {
        String toolName = delegate.getToolDefinition().name();
        try {
            return circuitBreaker.executeSupplier(() -> delegate.call(toolInput));
        } catch (CallNotPermittedException e) {
            log.warn("MCP 工具熔断中，跳过调用：{}", toolName);
            return "该工具服务暂时不可用（熔断保护中），请稍后再试";
        } catch (Exception e) {
            log.warn("MCP 工具调用失败：{}，原因：{}", toolName, e.getMessage());
            return "工具调用失败，请稍后再试";
        }
    }

    @Override
    public String call(String toolInput, ToolContext toolContext) {
        String toolName = delegate.getToolDefinition().name();
        try {
            return circuitBreaker.executeSupplier(() -> delegate.call(toolInput, toolContext));
        } catch (CallNotPermittedException e) {
            log.warn("MCP 工具熔断中，跳过调用：{}", toolName);
            return "该工具服务暂时不可用（熔断保护中），请稍后再试";
        } catch (Exception e) {
            log.warn("MCP 工具调用失败：{}，原因：{}", toolName, e.getMessage());
            return "工具调用失败，请稍后再试";
        }
    }
}
