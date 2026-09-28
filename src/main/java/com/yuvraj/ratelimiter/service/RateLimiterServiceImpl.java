package com.yuvraj.ratelimiter.service;

import com.yuvraj.ratelimiter.config.RateLimiterProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class RateLimiterServiceImpl implements RateLimiterService{
    private final RedisTokenBucketService redisTokenBucketService;
    private final RateLimiterProperties rateLimiterProperties;

    public boolean isAllowed(String clientId) {
        return redisTokenBucketService.isAllowed(clientId);
    }
    public long getCapacity(String clientId) {
        return redisTokenBucketService.getCapacity(clientId);
    }
    public long getAvailableTokens(String clientId) {
        return redisTokenBucketService.getAvailableTokens(clientId);
    }
}

// Client request = Gateway Filter (intercepts request) = Check Rate Limit
// Global Filters  =  applied to all routes
// Route Filters = applied to specific routes
// Custom filters = your own implementation of filters