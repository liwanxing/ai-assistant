package com.liwx.learning.rag;

import com.google.common.util.concurrent.RateLimiter;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Guava RateLimiter 令牌桶限流测试
 * 验证项目限流方案的核心行为，不启动 Spring 容器，纯单元测试
 *
 * 关键机制：RateLimiter 创建时桶是空的，但第一次 tryAcquire 能通过（预支机制），
 * 之后每次 tryAcquire 都要等令牌按速率产生出来，连续请求会被拒绝。
 */
class RateLimiterTest {

    /** 首次请求 → 通过（Guava 预支机制，创建时 nextFreeTicketMicros = now） */
    @Test
    void shouldAcquireOnFirstRequest() {
        RateLimiter limiter = RateLimiter.create(0.167);

        assertTrue(limiter.tryAcquire(), "首次请求应该通过");
    }

    /** 首次通过后，立即第二次请求 → 拒绝（令牌用完） */
    @Test
    void shouldRejectSecondImmediateRequest() {
        RateLimiter limiter = RateLimiter.create(0.167);
        limiter.tryAcquire();  // 消耗初始令牌

        assertFalse(limiter.tryAcquire(), "令牌用完后应立即拒绝");
    }

    /** 同一用户连续打 3 次，只有第 1 次通过 */
    @Test
    void shouldOnlyAllowFirstRequestWhenBurstThree() {
        RateLimiter limiter = RateLimiter.create(0.167);

        assertTrue(limiter.tryAcquire(), "第 1 次应通过");
        assertFalse(limiter.tryAcquire(), "第 2 次应拒绝");
        assertFalse(limiter.tryAcquire(), "第 3 次应拒绝");
    }

    /** 高速率下首次请求也只允许 1 次，立即第二次仍然拒绝（桶初始为空，没有积攒） */
    @Test
    void shouldRejectSecondImmediateRequestEvenAtHighRate() {
        RateLimiter limiter = RateLimiter.create(10);
        limiter.tryAcquire();  // 首次预支通过

        assertFalse(limiter.tryAcquire(), "高速率下立即第二次也应拒绝，需等待令牌产生");
    }

    /** 不同用户的限流器互相隔离（各自的桶独立计数） */
    @Test
    void shouldIsolateDifferentUsers() {
        RateLimiter user1Limiter = RateLimiter.create(0.167);
        RateLimiter user2Limiter = RateLimiter.create(0.167);

        user1Limiter.tryAcquire();  // user1 消耗令牌

        assertTrue(user2Limiter.tryAcquire(), "user2 的桶是独立的，首次请求应通过");
    }
}
