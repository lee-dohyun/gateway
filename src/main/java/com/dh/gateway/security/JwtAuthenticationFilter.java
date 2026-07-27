package com.dh.gateway.security;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;

import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import reactor.core.publisher.Mono;

/**
 * 게이트웨이는 leedohyun.com의 대부분 도메인(wordpress, tool, keycloak, minio, redmine,
 * architecture 등)을 프록시하는 공용 진입점이라 기본적으로 모든 요청을 통과시킨다.
 * PROTECTED_HOSTS 로 명시된 호스트(customer.leedohyun.com 로 인입되는 로그인/정적 리소스 제외한
 * 요청)만 ACCESS_TOKEN 쿠키의 JWT를 Keycloak(customer realm)의 공개키로 검증한다.
 * 검증 성공 시 토큰의 email/name 클레임을 X-User-Email/X-User-Name 헤더로 주입한다.
 * 검증 실패 시 home.leedohyun.com 으로 리다이렉트한다.
 */
@Component
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

    private static final Logger logger = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String ACCESS_TOKEN_COOKIE = "ACCESS_TOKEN";
    private static final String EXPECTED_ISSUER = "https://keycloak.leedohyun.com/realms/customer";
    private static final List<String> PROTECTED_HOSTS = List.of("customer.leedohyun.com");
    private static final List<String> PUBLIC_EXACT_PATHS =
            List.of("/api/auth/login", "/api/auth/signup", "/api/auth/logout");
    private static final List<String> PUBLIC_PATH_PREFIXES =
            List.of("/login", "/_next/", "/favicon.ico");

    private final WebClient keycloakClient;
    private final Map<String, RSAKey> keyCache = new ConcurrentHashMap<>();

    public JwtAuthenticationFilter(WebClient.Builder builder) {
        this.keycloakClient = builder
                .baseUrl("http://keycloak-service.keycloak.svc.cluster.local/realms/customer")
                .build();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String host = request.getURI().getHost();
        String path = request.getURI().getPath();

        if (!requiresAuth(host, path)) {
            return chain.filter(exchange);
        }

        HttpCookie cookie = request.getCookies().getFirst(ACCESS_TOKEN_COOKIE);
        if (cookie == null) {
            return redirectToHome(exchange);
        }

        return verify(cookie.getValue())
                .flatMap(claims -> {
                    ServerHttpRequest mutated = request.mutate()
                            .header("X-User-Email", safeString(claims.getClaim("email")))
                            .header("X-User-Name", safeString(claims.getClaim("name")))
                            .build();
                    return chain.filter(exchange.mutate().request(mutated).build());
                })
                .onErrorResume(e -> {
                    logger.debug("JWT 검증 실패: {}", e.getMessage());
                    return redirectToHome(exchange);
                });
    }

    private boolean requiresAuth(String host, String path) {
        if (host == null || !PROTECTED_HOSTS.contains(host)) {
            return false;
        }
        if (PUBLIC_EXACT_PATHS.contains(path)) {
            return false;
        }
        return PUBLIC_PATH_PREFIXES.stream().noneMatch(path::startsWith);
    }

    private Mono<JWTClaimsSet> verify(String token) {
        return Mono.fromCallable(() -> SignedJWT.parse(token))
                .flatMap(signedJwt -> resolveKey(signedJwt.getHeader().getKeyID())
                        .flatMap(rsaKey -> Mono.fromCallable(() -> {
                            boolean valid = signedJwt.verify(new RSASSAVerifier(rsaKey.toRSAPublicKey()));
                            if (!valid) {
                                throw new IllegalStateException("서명이 유효하지 않음");
                            }
                            JWTClaimsSet claims = signedJwt.getJWTClaimsSet();
                            if (claims.getExpirationTime() == null
                                    || claims.getExpirationTime().before(new java.util.Date())) {
                                throw new IllegalStateException("토큰 만료");
                            }
                            if (!EXPECTED_ISSUER.equals(claims.getIssuer())) {
                                throw new IllegalStateException("알 수 없는 issuer: " + claims.getIssuer());
                            }
                            return claims;
                        })));
    }

    private Mono<RSAKey> resolveKey(String kid) {
        RSAKey cached = keyCache.get(kid);
        if (cached != null) {
            return Mono.just(cached);
        }
        return keycloakClient.get()
                .uri("/protocol/openid-connect/certs")
                .retrieve()
                .bodyToMono(String.class)
                .timeout(Duration.ofSeconds(3))
                .flatMap(body -> Mono.fromCallable(() -> {
                    JWKSet jwkSet = JWKSet.parse(body);
                    RSAKey key = (RSAKey) jwkSet.getKeyByKeyId(kid);
                    if (key == null) {
                        throw new IllegalStateException("일치하는 kid를 JWKS에서 찾을 수 없음: " + kid);
                    }
                    keyCache.put(kid, key);
                    return key;
                }));
    }

    private Mono<Void> redirectToHome(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.FOUND);
        exchange.getResponse().getHeaders().setLocation(URI.create("https://home.leedohyun.com/"));
        return exchange.getResponse().setComplete();
    }

    private String safeString(Object value) {
        return value == null ? "" : value.toString();
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
