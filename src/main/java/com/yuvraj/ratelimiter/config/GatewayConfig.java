package com.yuvraj.ratelimiter.config;

//Defines how spring cloud gateway routes the incoming requests
// maps the client request to the backend service and then it applies filter
// Acting as reverse proxy

// Custom Route Locator

import com.yuvraj.ratelimiter.filter.TokenBucketRateLimiterFilter;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class GatewayConfig {
    private final RateLimiterProperties rateLimiterProperties;
    private final TokenBucketRateLimiterFilter tokenBucketRateLimiterFilter;
    public GatewayConfig(RateLimiterProperties rateLimiterProperties, TokenBucketRateLimiterFilter filter) {
        this.rateLimiterProperties = rateLimiterProperties;
        this.tokenBucketRateLimiterFilter = filter;
    }

    @Bean
    public RouteLocator customRouteLocator(RouteLocatorBuilder builder) {
        return builder.routes()
                .route("api-route",r->r.path("/api/**")
                        .filters(f->f.stripPrefix(1)
                                .filter(tokenBucketRateLimiterFilter.apply(new TokenBucketRateLimiterFilter.Config()))
                                )
                                .uri(rateLimiterProperties.getApiServerUrl())
                )
                .build();
    }
}
