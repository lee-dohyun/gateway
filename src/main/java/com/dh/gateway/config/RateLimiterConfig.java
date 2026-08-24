package com.dh.gateway.config;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import reactor.core.publisher.Mono;

import java.util.Objects;

@Configuration
public class RateLimiterConfig {

    /**
     * 기본 Rate Limiter KeyResolver (IP 기반)
     */
    @Bean
    @Primary
    public KeyResolver ipKeyResolver() {
        return exchange -> {
            String ip = exchange.getRequest().getRemoteAddress() != null ? 
                        exchange.getRequest().getRemoteAddress().getAddress().getHostAddress() : "unknown";
            return Mono.just(ip);
        };
    }

    /**
     * 로그인 사용자용 Rate Limiter KeyResolver (X-User-Id 헤더 기반)
     */
    @Bean
    public KeyResolver userKeyResolver() {
        return exchange -> {
            String userId = exchange.getRequest().getHeaders().getFirst("X-User-Id");
            if (userId != null && !userId.isEmpty()) {
                return Mono.just("user_" + userId);
            }
            // 미로그인 시 IP로 폴백
            String ip = exchange.getRequest().getRemoteAddress() != null ? 
                        exchange.getRequest().getRemoteAddress().getAddress().getHostAddress() : "unknown";
            return Mono.just("ip_" + ip);
        };
    }

    /**
     * 파트너사 API용 Rate Limiter KeyResolver (X-Partner-Id 헤더 기반)
     */
    @Bean
    public KeyResolver partnerKeyResolver() {
        return exchange -> {
            String partnerId = exchange.getRequest().getHeaders().getFirst("X-Partner-Id");
            if (partnerId != null && !partnerId.isEmpty()) {
                return Mono.just("partner_" + partnerId);
            }
            String ip = exchange.getRequest().getRemoteAddress() != null ? 
                        exchange.getRequest().getRemoteAddress().getAddress().getHostAddress() : "unknown";
            return Mono.just("anonymous_partner_" + ip);
        };
    }
}
