package com.p2p.payment.service;

import com.p2p.payment.exception.RateLimitExceededException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RRateLimiter;
import org.redisson.api.RateIntervalUnit;
import org.redisson.api.RateType;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class RateLimitService {

    private final RedissonClient redissonClient;

    @Value("${app.rate-limit.transfers-per-minute}")
    private long transfersPerMinute;

    public void checkTransferRateLimit(UUID userId) {
        String key = "rate:transfer:" + userId;
        RRateLimiter limiter = redissonClient.getRateLimiter(key);

        // Idempotent — only sets rate if not already configured
        limiter.trySetRate(RateType.OVERALL, transfersPerMinute, 1, RateIntervalUnit.MINUTES);

        if (!limiter.tryAcquire()) {
            log.warn("Rate limit exceeded for userId={}", userId);
            throw new RateLimitExceededException(
                    "Too many transfer requests. Maximum " + transfersPerMinute + " per minute.");
        }
    }
}
