package com.liwx.learning.aspect;

import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * AOP 日志切面：所有 Controller 方法自动记录方法名、参数、耗时、返回值
 */
@Slf4j
@Aspect
@Component
public class WebLogAspect {

    /**
     * 环绕通知：包住目标方法，前面记开始时间，后面记耗时和返回值
     */
    @Around("execution(* com.liwx.learning..controller..*.*(..))")
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
