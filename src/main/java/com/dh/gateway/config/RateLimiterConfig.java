package com.dh.gateway.config;

import java.net.InetSocketAddress;

import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

@Configuration
public class RateLimiterConfig {

    /**
     * getRemoteAddress()가 non-null이어도 getAddress()(InetAddress)는 unresolved 주소일 때 null일
     * 수 있다(프록시 경유 시 발생) — 이 경우 getHostString()으로 폴백한다(gateway#260, NPE 회귀).
     */
    private static String resolveClientAddress(ServerWebExchange exchange) {
        InetSocketAddress remoteAddress = exchange.getRequest().getRemoteAddress();
        if (remoteAddress == null) {
            return "unknown";
        }
        if (remoteAddress.getAddress() != null) {
            return remoteAddress.getAddress().getHostAddress();
        }
        return remoteAddress.getHostString() != null ? remoteAddress.getHostString() : "unknown";
    }

    /**
     * 기본 Rate Limiter KeyResolver (IP 기반)
     */
    @Bean
    @Primary
    public KeyResolver ipKeyResolver() {
        return exchange -> Mono.just(resolveClientAddress(exchange));
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
            return Mono.just("ip_" + resolveClientAddress(exchange));
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
            return Mono.just("anonymous_partner_" + resolveClientAddress(exchange));
        };
    }
}
