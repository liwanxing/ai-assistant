package com.liwx.learning.aspect;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * AOP 日志切面
 *
 * AOP 三步走：
 *   1. @Aspect     → 声明这是一个切面
 *   2. @Around(...) → 切点（给哪些方法加）+ 通知（什么时候加）
 *   3. 方法体       → 具体加什么功能
 *
 * 做了什么：
 *   所有 Controller 方法执行时，自动记录：方法名、参数、耗时、返回值
 *   不用改任何 Controller 代码，日志自动加上
 */
@Slf4j
@Aspect
@Component
public class WebLogAspect {

    /**
     * 环绕通知：包住目标方法，前面记开始时间，后面记耗时和返回值
     *
     * 切点表达式解释：
     *   execution(* com.liwx.learning.controller..*.*(..))
     *   → com.liwx.learning.controller 包及子包下的所有类的所有方法
     */
    @Around("execution(* com.liwx.learning.controller..*.*(..))")
    public Object logAround(ProceedingJoinPoint joinPoint) throws Throwable {
        // 方法执行前：记录方法名和参数
        String methodName = joinPoint.getSignature().toShortString();
        Object[] args = joinPoint.getArgs();
        log.info("调用接口: {}, 参数: {}", methodName, Arrays.toString(args));

        long startTime = System.currentTimeMillis();

        try {
            // 执行目标方法（不调这行，原方法就不会执行）
            Object result = joinPoint.proceed();

            // 方法执行后：记录耗时和返回值
            long costTime = System.currentTimeMillis() - startTime;
            log.info("接口返回: {}, 耗时: {}ms", methodName, costTime);
            return result;
        } catch (Throwable e) {
            // 方法抛异常：记录异常和耗时
            long costTime = System.currentTimeMillis() - startTime;
            log.error("接口异常: {}, 耗时: {}ms, 异常: {}", methodName, costTime, e.getMessage());
            throw e;
        }
    }
}
