package com.liwx.learning.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

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
        .excludePathPatterns("/login", "/ai/test");
    }
}
