package com.liwx.learning.config;

import cn.dev33.satoken.interceptor.SaInterceptor;
import cn.dev33.satoken.stp.StpUtil;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Sa-Token 路由拦截配置
 *
 * 原理：注册一个 SaInterceptor（Sa-Token 提供的拦截器），
 * 拦截所有请求，在请求到达 Controller 之前检查是否已登录
 * 没登录的会被拦截，抛 NotLoginException
 */
@Configuration
public class SaTokenConfig implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new SaInterceptor(handler -> StpUtil.checkLogin()))
                .addPathPatterns("/**")           // 拦截所有路径
                // /rag/ask 是 SSE 流式接口，异步 dispatch 时 Sa-Token 上下文已销毁会报错，必须排除
                .excludePathPatterns("/login", "/ai/test", "/rag/ask");
    }
}
