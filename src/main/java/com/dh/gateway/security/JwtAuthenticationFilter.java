package com.dh.gateway.security;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
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
 * 게이트웨이는 여러 도메인(wordpress, tool, keycloak, minio, redmine, architecture,
 * 쇼핑몰 프론트 등)을 프록시하는 공용 진입점이라 기본적으로 모든 요청을 통과시킨다.
 * {@link GatewaySecurityProperties#getProtectedHosts()}에 명시된 호스트(로그인/정적 리소스
 * 제외한 요청)만 ACCESS_TOKEN 쿠키의 JWT를 Keycloak(customer realm)의 공개키로 검증한다.
 * 검증 성공 시 토큰의 email/name 클레임을 X-User-Email/X-User-Name 헤더로 주입한다.
 * 검증 실패 시 {@link GatewaySecurityProperties#getRedirectUrl()}로 리다이렉트한다.
 * {@link GatewaySecurityProperties#getOptionalAuthHosts()}는 로그인을 강제하지 않되, 쿠키가
 * 있으면 검증해서 헤더를 주입한다 — 비로그인 사용자도 상품/장바구니는 그대로 쓰되, 로그인된 경우에만
 * 주문에 계정을 연결하기 위함.
 *
 * 호스트/issuer 값은 전부 {@link GatewaySecurityProperties}(application*.yml의
 * gateway.security.*)에서 온다 — 쇼핑몰이 다른 도메인으로 옮기거나 보호 대상 도메인이 늘어나도
 * 이 클래스는 건드릴 필요 없이 설정만 바꾸면 된다.
 */
@Component
public class JwtAuthenticationFilter implements GlobalFilter, Ordered {

    private static final Logger logger = LoggerFactory.getLogger(JwtAuthenticationFilter.class);
    private static final String ACCESS_TOKEN_COOKIE = "ACCESS_TOKEN";
    private static final String HOME_AUTH_ME_PATH = "/api/auth/me";
    private static final List<String> PUBLIC_EXACT_PATHS =
            List.of("/api/auth/login", "/api/auth/signup", "/api/auth/logout",
                    "/api/auth/verify-email", "/api/auth/resend-verification",
                    "/verify");
    private static final List<String> PUBLIC_PATH_PREFIXES =
            // /icon.svg: Next.js App Router의 파일 기반 favicon 라우트(app/icon.svg) — 로그인 전
            // 페이지(/login 등)도 <link rel="icon">으로 이걸 참조하는데 빠져 있어서 비로그인 사용자는
            // 파비콘 요청 자체가 홈으로 리다이렉트되어 파비콘이 안 뜨는 문제가 있었음.
            List.of("/login", "/signup", "/_next/", "/favicon.ico", "/icon.svg");

    private final GatewaySecurityProperties properties;
    private final WebClient keycloakClient;
    private final Map<String, RSAKey> keyCache = new ConcurrentHashMap<>();

    public JwtAuthenticationFilter(WebClient.Builder builder, GatewaySecurityProperties properties) {
        this.properties = properties;
        this.keycloakClient = builder
                .baseUrl(properties.getKeycloakRealmUrl())
                .build();
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String host = request.getURI().getHost();
        String path = request.getURI().getPath();

        if (isOptionalAuthPath(host, path) || properties.getOptionalAuthHosts().contains(host)) {
            return attachUserHeadersIfPresent(exchange, chain);
        }

        if (!requiresAuth(host, path)) {
            return chain.filter(exchange);
        }

        HttpCookie cookie = request.getCookies().getFirst(ACCESS_TOKEN_COOKIE);
        if (cookie == null) {
            return redirectToHome(exchange);
        }

        return verify(cookie.getValue())
                .flatMap(claims -> chain.filter(exchange.mutate().request(withUserHeaders(request, claims)).build()))
                .onErrorResume(e -> {
                    logger.debug("JWT 검증 실패: {}", e.getMessage());
                    return redirectToHome(exchange);
                });
    }

    /**
     * 로그인 상태 조회(/api/auth/me)는 어느 호스트에서 호출되든 로그인을 강제하지 않는다.
     * 쿠키가 있으면 검증해서 사용자 헤더를 주입하고, 없거나 유효하지 않으면 헤더 없이 그대로 통과시켜
     * auth-api가 401(비로그인)을 응답하게 둔다. customer.posselect.com은 protected-hosts라 기본은
     * 강제 로그인이지만, 공유 Header 컴포넌트가 로그인 여부를 확인하려고 모든 페이지(로그인 전 상태의
     * /login, /signup 포함)에서 이 경로를 호출하므로 이 경로만 예외로 둔다 — 다른 경로(마이페이지 등)의
     * 보호는 그대로 유지된다.
     */
    private boolean isOptionalAuthPath(String host, String path) {
        if (!HOME_AUTH_ME_PATH.equals(path)) {
            return false;
        }
        return properties.getHomeHost().equals(host) || properties.getProtectedHosts().contains(host);
    }

    private Mono<Void> attachUserHeadersIfPresent(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        HttpCookie cookie = request.getCookies().getFirst(ACCESS_TOKEN_COOKIE);
        if (cookie == null) {
            return chain.filter(exchange);
        }
        return verify(cookie.getValue())
                .flatMap(claims -> chain.filter(exchange.mutate().request(withUserHeaders(request, claims)).build()))
                .onErrorResume(e -> chain.filter(exchange));
    }

    private ServerHttpRequest withUserHeaders(ServerHttpRequest request, JWTClaimsSet claims) {
        return request.mutate()
                .header("X-User-Email", safeString(claims.getClaim("email")))
                .header("X-User-Name", urlEncode(safeString(claims.getClaim("name"))))
                .build();
    }

    private boolean requiresAuth(String host, String path) {
        if (host == null || !properties.getProtectedHosts().contains(host)) {
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
                            if (!properties.getKeycloakIssuer().equals(claims.getIssuer())) {
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
        exchange.getResponse().getHeaders().setLocation(URI.create(properties.getRedirectUrl()));
        return exchange.getResponse().setComplete();
    }

    private String safeString(Object value) {
        return value == null ? "" : value.toString();
    }

    /** HTTP 헤더는 ISO-8859-1만 안전하므로, 한글 등 비-ASCII 값(name 클레임)은 URL 인코딩해서 넣는다. */
    private String urlEncode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE + 10;
    }
}
