package com.yuvraj.ratelimiter.service;

import com.yuvraj.ratelimiter.config.RateLimiterProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

// Store Token Bucket State in Redis
// Manage Token per client
// Handle the Token Refill based on time
// We Provide rate limiting logic

@Service
@RequiredArgsConstructor
public class RedisTokenBucketService {

    private final JedisPool jedisPool;
    private final RateLimiterProperties limiterProperties;

    private final String TOKENS_KEY_PREFIX = "rate_limiter:tokens:";
    private static final String LAST_REFILL_KEY_PREFIX = "rate_limiter:last_refill:";

    // Pattern
    // rate_limiter:{type}:{clientId}
    // rate_limiter:tokens:192.168.0.100 > Current Token Count
    // rate_limiter:last_refill:192.168.0.100 > Last Refill TimeStamp

    public boolean isAllowed(String clientId){
//        We are going to check request from client is allowed or not
        String tokensKey = TOKENS_KEY_PREFIX + clientId;

        try(Jedis jedis = jedisPool.getResource()){
            refillTokens(clientId,jedis);

            String tokenStr = jedis.get(tokensKey);

            long currentTokens = tokenStr != null ? Long.parseLong(tokenStr) : limiterProperties.getCapacity();

            if(currentTokens<=0){
                return false;
            }

            long decremented = jedis.decr(tokensKey);

            return decremented >= 0;
        }
    }

    public long getCapacity(String clientId){
        return limiterProperties.getCapacity();
    }

    public long getAvailableTokens(String clientId){
        String tokensKey = TOKENS_KEY_PREFIX + clientId;

        try(Jedis jedis = jedisPool.getResource())
        {
            refillTokens(clientId,jedis);
            String tokenStr = jedis.get(tokensKey);
            return tokenStr != null ? Long.parseLong(tokenStr) : limiterProperties.getCapacity();
        }
    }

    private void refillTokens(String clientId, Jedis jedis){
        String tokensKey = TOKENS_KEY_PREFIX + clientId;
        String lastRefillKey = LAST_REFILL_KEY_PREFIX + clientId;

        long now = System.currentTimeMillis();
        String lastRefillTime = jedis.get(lastRefillKey);
        if(lastRefillTime == null){
            jedis.set(tokensKey, String.valueOf(limiterProperties.getCapacity()));
            jedis.set(lastRefillKey, String.valueOf(now));
            return;
        }

        long lastRefill = Long.parseLong(lastRefillTime);
        long elapsedTime = now - lastRefill;

        if(elapsedTime<=0)return;

        long tokensToAdd = (elapsedTime)* limiterProperties.getRefillRate()/1000;
//        tokensToAdd = (elapsedTime)* limiterProperties.getRefillRate()/1000;
//        Elapsed Time in miliseconds i.e why divided by 1000
//        refillRate is tokens per second
//        Converted msec to sec so diveide by 1000

//        Example : Elapsed Time > 2000ms, Refill Rate = 5 tokens per second, Cal = 2000*5/1000 = 10
        if(tokensToAdd<=0)return;

        String tokenStr = jedis.get(tokensKey);

        long currentTokens = tokenStr != null ? Long.parseLong(tokenStr) : limiterProperties.getCapacity();
        long newTokens = Math.min(tokensToAdd+currentTokens, limiterProperties.getCapacity());

        jedis.set(tokensKey, String.valueOf(newTokens));
        jedis.set(lastRefillKey, String.valueOf(now));

    }
}
