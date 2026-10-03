package com.dh.gateway;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.cloud.gateway.support.ServerWebExchangeUtils.GATEWAY_REQUEST_URL_ATTR;

import java.net.URI;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.cloud.gateway.filter.RouteToRequestUrlFilter;
import org.springframework.context.annotation.Bean;
import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import reactor.core.publisher.Mono;
import reactor.netty.DisposableServer;
import reactor.netty.http.server.HttpServer;

/**
 * 게이트웨이를 실제 포트로 띄우고 HTTP 요청을 끝까지 흘려 보는 테스트 (gateway#310, architecture#14).
 *
 * <p>{@code JwtAuthenticationFilter*Test} 는 필터 하나를 목 체인으로 부르고, {@code *RouteTest} 는 라우트
 * 정의의 순서만 본다. 여기서는 그 둘이 못 보는 것 - <b>백엔드에 실제로 무엇이 도착하는가</b> - 를 본다.
 * 위조 신원 헤더 통과(msa #87), 화이트리스트 누락, 쓰기 차단 라우트 순서 사고가 전부 이 층에서 났다.
 *
 * <p>application.yml 의 라우트는 그대로 쓴다. 백엔드 주소(클러스터 DNS)만 테스트용 필터가 로컬 스텁으로
 * 바꾸고, 원래 가려던 호스트는 {@code X-Test-Upstream} 헤더로 스텁에 알려 준다. 스텁은 받은 요청을
 * JSON 으로 되돌려 주고 Keycloak JWKS 엔드포인트도 겸한다.
 *
 * <p>Redis 는 띄우지 않는다. 닫힌 포트로 돌려 레이트리밋이 즉시 실패-허용되게 한다 - 레이트리밋 자체는
 * 이 테스트의 대상이 아니다.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                // 8081 고정이면 같은 머신에서 동시에 도는 다른 테스트 JVM 과 포트가 부딪친다.
                "management.server.port=0",
                "spring.data.redis.host=localhost",
                "spring.data.redis.port=1"
        })
class GatewayHttpIntegrationTest {

    private static final String ISSUER = "https://keycloak.posselect.com/realms/customer";
    private static final String KEY_ID = "test-key";
    private static final String CUSTOMER = "customer.posselect.com";
    private static final String PRODUCT = "product.posselect.com";
    private static final String HOME = "home.posselect.com";
    private static final String ADMIN = "admin.posselect.com";
    private static final String UPSTREAM_HEADER = "X-Test-Upstream";

    private static final ObjectMapper JSON = new ObjectMapper();
    /** 스텁 백엔드에 도착한 요청의 "원래 호스트 + 경로" 기록. JWKS 조회는 넣지 않는다. */
    private static final List<String> ARRIVED = new CopyOnWriteArrayList<>();
    private static final RSAKey SIGNING_KEY = newKey();
    private static final DisposableServer STUB = startStub();

    @Autowired
    private WebTestClient client;

    @DynamicPropertySource
    static void pointKeycloakAtStub(DynamicPropertyRegistry registry) {
        registry.add("gateway.security.keycloak-realm-url",
                () -> "http://localhost:" + STUB.port() + "/realms/customer");
    }

    @AfterAll
    static void stopStub() {
        STUB.disposeNow();
    }

    @BeforeEach
    void forgetArrivals() {
        ARRIVED.clear();
    }

    /** application.yml 이 가리키는 클러스터 주소를 스텁으로 바꾼다. 라우트 선택·필터는 운영 그대로 탄다. */
    @TestConfiguration
    static class RouteUpstreamsToStub {
        @Bean
        GlobalFilter upstreamToStubFilter() {
            return new UpstreamToStubFilter();
        }
    }

    static class UpstreamToStubFilter implements GlobalFilter, Ordered {
        @Override
        public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
            URI upstream = exchange.getAttribute(GATEWAY_REQUEST_URL_ATTR);
            if (upstream == null || !"http".equals(upstream.getScheme())) {
                return chain.filter(exchange); // no://op (리다이렉트·차단), forward:/fallback
            }
            URI stub = UriComponentsBuilder.fromUri(upstream)
                    .host("localhost").port(STUB.port()).build(true).toUri();
            exchange.getAttributes().put(GATEWAY_REQUEST_URL_ATTR, stub);
            return chain.filter(exchange.mutate()
                    .request(exchange.getRequest().mutate().header(UPSTREAM_HEADER, upstream.getHost()).build())
                    .build());
        }

        @Override
        public int getOrder() {
            return RouteToRequestUrlFilter.ROUTE_TO_URL_FILTER_ORDER + 1;
        }
    }

    private static DisposableServer startStub() {
        return HttpServer.create().port(0).handle((request, response) -> {
            response.header("Content-Type", "application/json");
            if (request.uri().endsWith("/protocol/openid-connect/certs")) {
                return response.sendString(Mono.just(new JWKSet(SIGNING_KEY.toPublicJWK()).toString()));
            }
            Map<String, String> headers = new TreeMap<>();
            request.requestHeaders().forEach(h -> headers.put(h.getKey().toLowerCase(), h.getValue()));
            ARRIVED.add(headers.get(UPSTREAM_HEADER.toLowerCase()) + request.uri());
            return response.sendString(Mono.fromCallable(() -> JSON.writeValueAsString(
                    Map.of("method", request.method().name(), "path", request.uri(), "headers", headers))));
        }).bindNow();
    }

    private static RSAKey newKey() {
        try {
            return new RSAKeyGenerator(2048).keyID(KEY_ID).generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String token(RSAKey key, String issuer, Instant expiresAt) {
        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KEY_ID).build(),
                    new JWTClaimsSet.Builder()
                            .subject("user-sub-1")
                            .issuer(issuer)
                            .expirationTime(Date.from(expiresAt))
                            .claim("email", "buyer@example.com")
                            .claim("name", "홍길동")
                            .build());
            jwt.sign(new RSASSASigner(key));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String validToken() {
        return token(SIGNING_KEY, ISSUER, Instant.now().plusSeconds(300));
    }

    private void assertRedirectedToLogin(String cookie) {
        client.get().uri("/mypage").header("Host", CUSTOMER).header("Cookie", "ACCESS_TOKEN=" + cookie)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.FOUND)
                .expectHeader().valueEquals("Location",
                        "https://customer.posselect.com/login?redirect_uri=https%3A%2F%2Fcustomer.posselect.com%2Fmypage");
        assertThat(ARRIVED).isEmpty();
    }

    @Test
    void 보호_호스트는_쿠키가_없으면_백엔드에_닿지_않고_로그인으로_302된다() {
        client.get().uri("/mypage?tab=orders").header("Host", CUSTOMER)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.FOUND)
                .expectHeader().valueEquals("Location", "https://customer.posselect.com/login?redirect_uri="
                        + "https%3A%2F%2Fcustomer.posselect.com%2Fmypage%3Ftab%3Dorders");

        assertThat(ARRIVED).isEmpty();
    }

    @Test
    void 유효한_토큰이면_신원_헤더를_게이트웨이가_채우고_클라이언트가_보낸_값은_버린다() {
        String token = validToken();

        client.get().uri("/mypage").header("Host", CUSTOMER)
                .header("Cookie", "ACCESS_TOKEN=" + token)
                .header("X-User-Id", "attacker")
                .header("X-User-Email", "attacker@example.com")
                .header("X-User-Role", "SYSTEM_ADMIN")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.headers['x-test-upstream']").isEqualTo("customer-front.customer.svc.cluster.local")
                .jsonPath("$.headers['x-user-id']").isEqualTo("user-sub-1")
                .jsonPath("$.headers['x-user-email']").isEqualTo("buyer@example.com")
                .jsonPath("$.headers['x-user-name']").isEqualTo("%ED%99%8D%EA%B8%B8%EB%8F%99")
                .jsonPath("$.headers['x-user-role']").doesNotExist()
                .jsonPath("$.headers['authorization']").isEqualTo("Bearer " + token);
    }

    @Test
    void 만료된_토큰은_거부된다() {
        assertRedirectedToLogin(token(SIGNING_KEY, ISSUER, Instant.now().minusSeconds(60)));
    }

    @Test
    void 다른_realm_이_발급한_토큰은_거부된다() {
        assertRedirectedToLogin(token(SIGNING_KEY,
                "https://keycloak.posselect.com/realms/staff", Instant.now().plusSeconds(300)));
    }

    @Test
    void 같은_kid_를_달았어도_다른_키로_서명한_토큰은_거부된다() {
        assertRedirectedToLogin(token(newKey(), ISSUER, Instant.now().plusSeconds(300)));
    }

    @Test
    void 로그인_전_공개_경로는_쿠키_없이_auth_api_에_닿고_위조_신원_헤더는_제거된다() {
        client.post().uri("/api/auth/login").header("Host", CUSTOMER)
                .header("X-User-Id", "attacker")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.method").isEqualTo("POST")
                .jsonPath("$.headers['x-test-upstream']").isEqualTo("auth-api.customer.svc.cluster.local")
                .jsonPath("$.headers['x-user-id']").doesNotExist();
    }

    @Test
    void 로그인_상태_조회는_보호_호스트에서도_302_없이_헤더_없는_채로_auth_api_에_닿는다() {
        client.get().uri("/api/auth/me").header("Host", CUSTOMER)
                .header("X-User-Id", "attacker")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.headers['x-test-upstream']").isEqualTo("auth-api.customer.svc.cluster.local")
                .jsonPath("$.headers['x-user-id']").doesNotExist();
    }

    @Test
    void 선택_인증_호스트는_쿠키가_없어도_통과하되_위조_신원_헤더는_제거된다() {
        client.get().uri("/api/orders/1").header("Host", PRODUCT)
                .header("X-User-Id", "attacker")
                .header("X-User-Email", "attacker@example.com")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.headers['x-test-upstream']").isEqualTo("order-api.customer.svc.cluster.local")
                .jsonPath("$.headers['x-user-id']").doesNotExist()
                .jsonPath("$.headers['x-user-email']").doesNotExist();
    }

    @Test
    void 선택_인증_호스트는_유효한_토큰이_있으면_신원_헤더를_채운다() {
        client.get().uri("/api/orders/1").header("Host", PRODUCT)
                .header("Cookie", "ACCESS_TOKEN=" + validToken())
                .header("X-User-Id", "attacker")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.headers['x-user-id']").isEqualTo("user-sub-1");
    }

    @Test
    void 인증을_요구하지_않는_호스트에서도_위조_신원_헤더는_제거된다() {
        client.get().uri("/").header("Host", HOME)
                .header("X-User-Id", "attacker")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.headers['x-test-upstream']").isEqualTo("store-front.customer.svc.cluster.local")
                .jsonPath("$.headers['x-user-id']").doesNotExist();
    }

    @Test
    void 프론트_호스트로_들어온_쓰기_요청은_백엔드에_닿지_않고_403이다() {
        for (String host : List.of(HOME, CUSTOMER, PRODUCT, ADMIN)) {
            client.post().uri("/some/page").header("Host", host)
                    .header("Cookie", "ACCESS_TOKEN=" + validToken())
                    .exchange()
                    .expectStatus().isForbidden();
        }

        assertThat(ARRIVED).isEmpty();
    }

    @Test
    void 메인_페이지의_찜_쓰기는_쓰기_차단보다_먼저_잡혀_product_api_에_닿는다() {
        client.post().uri("/api/wishlists/42").header("Host", HOME)
                .header("Cookie", "ACCESS_TOKEN=" + validToken())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.method").isEqualTo("POST")
                .jsonPath("$.headers['x-test-upstream']").isEqualTo("product-api.customer.svc.cluster.local")
                .jsonPath("$.headers['x-user-id']").isEqualTo("user-sub-1");
    }

    @Test
    void 관리자_호스트의_api_쓰기는_차단_규칙을_타지_않고_admin_front_에_닿는다() {
        client.post().uri("/api/products").header("Host", ADMIN)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.headers['x-test-upstream']").isEqualTo("admin-front.customer.svc.cluster.local");
    }
}
