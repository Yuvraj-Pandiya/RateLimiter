package com.yuvraj.ratelimiter.service;

import com.yuvraj.ratelimiter.config.RateLimiterProperties;

public interface RateLimiterService {
    boolean isAllowed(String clientId);
    long getCapacity(String clientId);
    long getAvailableTokens(String clientId);
}
