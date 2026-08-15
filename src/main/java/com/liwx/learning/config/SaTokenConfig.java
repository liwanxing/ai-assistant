package com.liwx.learning.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.nio.file.Path;

/**
 * Sa-Token 路由拦截配置
 *
 * 原理：注册一个 SaInterceptor（Sa-Token 提供的拦截器），
 * 拦截所有请求，在请求到达 Controller 之前检查是否已登录
 * 没登录的会被拦截，抛 NotLoginException
 *
 * SSE 流式接口（如 /rag/ask）不需要单独排除：
 *   流式响应完成后 Spring 会触发 async dispatch（第二次经过拦截器），
 *   此时 HTTP 上下文已销毁，用 DispatcherType.ASYNC 判断跳过这次拦截即可
 */
@Configuration
public class SaTokenConfig implements WebMvcConfigurer {

    @Value("${rag.upload-dir:./uploads}")
    private String uploadDir;

    /**
     * 静态资源映射：让 /uploads/** URL 能访问到磁盘上的上传文件
     * 聊天图片用 <img src="/uploads/chat-images/xxx.png"> 直接加载，不走 Controller
     */
    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        String resourceLocation = Path.of(uploadDir).toAbsolutePath().toUri().toString();
        registry.addResourceHandler("/uploads/**")
                .addResourceLocations(resourceLocation);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        SaInterceptor saInterceptor = new SaInterceptor(handler -> StpUtil.checkLogin());

        // 包装 SaInterceptor：跳过 async dispatch（SSE 流式完成后的异步转发）
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
                if (request.getDispatcherType() == DispatcherType.ASYNC) {
                    return true;  // async dispatch 不校验登录（上下文已销毁，校验必报错）
                }
                return saInterceptor.preHandle(request, response, handler);
            }
        })
        .addPathPatterns("/**")
        .excludePathPatterns(
                "/login",
                "/doc.html",              // Knife4j 文档页面
                "/webjars/**",            // Knife4j 静态资源
                "/v3/api-docs/**",        // OpenAPI JSON
                "/favicon.ico",
                "/swagger-ui/**",         // Swagger UI 资源
                "/swagger-resources/**",  // Swagger 资源
                "/uploads/chat-images/**",   // 聊天图片：前端 <img> 直接访问，不走 Controller 无法携带 token
                "/mcp"                       // MCP Server 端点（Streamable HTTP）：外部 MCP 客户端（Claude Desktop / Cursor）不会携带登录 token，
                                              // MCP 协议本身不含业务认证，生产环境应在网关层做 IP 白名单 / mTLS，而不是在这里搞登录态
        );
    }
}
