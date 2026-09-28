package com.yuvraj.ratelimiter.filter;

import com.yuvraj.ratelimiter.service.RateLimiterService;
import lombok.AllArgsConstructor;
import org.springframework.cloud.gateway.filter.GatewayFilter;
import org.springframework.cloud.gateway.filter.factory.AbstractGatewayFilterFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;

/*
* // Client request = Gateway Filter (intercepts request) = Check Rate Limit
 Global Filters  =  applied to all routes
 Route Filters = applied to specific routes
 Custom filters = your own implementation of filters
* */
@Component
@AllArgsConstructor
public class TokenBucketRateLimiterFilter extends AbstractGatewayFilterFactory<TokenBucketRateLimiterFilter.Config>{

    private final RateLimiterService rateLimiterService;

    @Override
    public TokenBucketRateLimiterFilter.Config newConfig() {
        return new TokenBucketRateLimiterFilter.Config();
    }

    @Override
    public GatewayFilter apply(TokenBucketRateLimiterFilter.Config config) {
        return (exchange, chain) -> {
            ServerHttpRequest request = (ServerHttpRequest) exchange.getRequest();
            ServerHttpResponse response = exchange.getResponse();
            String clientId = getClientId(request);
            if(!rateLimiterService.isAllowed(clientId)){
                response.setStatusCode(HttpStatus.TOO_MANY_REQUESTS);
                addRateLimitHeaders(response,clientId);

                String errorBody = String.format(
                        "{\"error\":\"Rate limited Exceeded\",\"clientId\":\"%s\"}", clientId
                );

                return response.writeWith(
                        Mono.just(response.bufferFactory().wrap(errorBody.getBytes(StandardCharsets.UTF_8)))
                );
            }
            return  chain.filter(exchange).then(Mono.fromRunnable(() -> {
                addRateLimitHeaders(response,clientId);
            }));
        };
    }

    public String getClientId(ServerHttpRequest request) {
        String xForwardedFor = request.getHeaders().getFirst("X-Forwarded-For");
        if(xForwardedFor != null && !xForwardedFor.isEmpty()){
            return xForwardedFor.split(",")[0].trim();
        }
        var remoteAddr = request.getRemoteAddress();
        if(remoteAddr != null && remoteAddr.getHostName() != null){
            return remoteAddr.getAddress().getHostAddress();
        }

        return "unknown";
    }

    private void addRateLimitHeaders(ServerHttpResponse response, String clientId){
        response.getHeaders().add("X-Rate-Limit-Limit",
                String.valueOf(rateLimiterService.getCapacity(clientId)));
        response.getHeaders().add("X-Rate-Limit-Remaining",
                String.valueOf(rateLimiterService.getAvailableTokens(clientId)));
    }
    public static class Config {

    }
}