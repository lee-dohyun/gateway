package com.dh.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteDefinition;
import org.springframework.cloud.gateway.route.RouteDefinitionLocator;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * admin.posselect.com / partner.posselect.com 의 {@code POST /api/login} 에 붙는 레이트리밋 라우트를 고정한다
 * (gateway#327).
 *
 * <p>이 두 BFF 는 로그인 때 Keycloak 을 클러스터 내부 주소로 직접 부른다 - 공개 Keycloak 호스트를 막는
 * {@code keycloak-internal-only-block} 을 거치지 않으므로, 이 경로를 지키는 건 Keycloak 의 로그인 시도 제한과
 * 여기의 레이트리밋뿐이다. Keycloak 제한은 <b>존재하는</b> 계정만 잠그기 때문에, 없는 사용자명을 계속 바꿔 가며
 * 찔러 보는 시도를 줄이는 쪽은 이 라우트다.
 *
 * <p>한도 숫자(replenishRate 1 / requestedTokens 10 / burstCapacity 50)는 일부러 여기 박아 둔다. 느슨하게 바꾸는
 * 일은 있을 수 있지만 조용히 바뀌면 안 된다. 숫자가 실제 Redis 에서 "연속 5회, 이후 10초에 1회"로 동작하는지는
 * {@link LoginRateLimitRedisTest} 가 본다.
 */
@SpringBootTest
class LoginRateLimitRouteTest {

    private static final String ADMIN_HOST = "admin.posselect.com";
    private static final String PARTNER_HOST = "partner.posselect.com";

    @Autowired
    private RouteLocator routeLocator;

    @Autowired
    private RouteDefinitionLocator routeDefinitionLocator;

    private String firstMatchingRouteId(HttpMethod method, String host, String rawPath) {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.method(method, URI.create("https://" + host + rawPath))
                        .header(HttpHeaders.HOST, host));
        return routeLocator.getRoutes()
                .filterWhen(route -> Mono.from(route.getPredicate().apply(exchange)))
                .map(Route::getId)
                .blockFirst();
    }

    static Stream<Arguments> loginRequests() {
        return Stream.of(
                Arguments.of(ADMIN_HOST, "/api/login", "admin-front-login"),
                Arguments.of(PARTNER_HOST, "/api/login", "partner-front-login"),
                // 끝 슬래시: Next.js 는 지금 308 로 /api/login 에 돌려보낸다(2026-10-24 실측) - 로그인 시도는 아니지만
                // 앱 설정이 바뀌어도 한도가 유지되도록 같은 라우트가 받는다. PathPattern 은 끝 슬래시를 기본으로 안 맞춰 준다.
                Arguments.of(ADMIN_HOST, "/api/login/", "admin-front-login"),
                Arguments.of(PARTNER_HOST, "/api/login/", "partner-front-login"),
                // percent-encoding: 게이트웨이는 디코딩해서 맞춘다. Next.js 는 %6Cogin 을 404 로 처리하지만(실측),
                // 앱이 디코딩 방식을 바꿔도 한도가 비켜 가지 않는다는 것을 고정한다.
                Arguments.of(ADMIN_HOST, "/api/%6Cogin", "admin-front-login"),
                Arguments.of(PARTNER_HOST, "/api/%6Cogin", "partner-front-login"));
    }

    static Stream<Arguments> otherRequests() {
        return Stream.of(
                // 로그인이 아닌 /api/** 는 기존 라우트가 그대로 받는다 (한도 없음)
                Arguments.of(HttpMethod.POST, ADMIN_HOST, "/api/admin/orders", "admin-front-api"),
                Arguments.of(HttpMethod.POST, ADMIN_HOST, "/api/logout", "admin-front-api"),
                Arguments.of(HttpMethod.POST, PARTNER_HOST, "/api/partner/products", "partner-front-api"),
                Arguments.of(HttpMethod.POST, PARTNER_HOST, "/api/logout", "partner-front-api"),
                // 로그인 라우트는 POST 만 본다 - 다른 메서드는 앱이 자기 응답(405)을 주게 둔다
                Arguments.of(HttpMethod.GET, ADMIN_HOST, "/api/login", "admin-front-api"),
                Arguments.of(HttpMethod.GET, PARTNER_HOST, "/api/login", "partner-front-api"),
                // 페이지 경로로 오는 쓰기 요청은 기존대로 차단
                Arguments.of(HttpMethod.POST, ADMIN_HOST, "/", "admin-front-block-write"),
                Arguments.of(HttpMethod.POST, PARTNER_HOST, "/", "partner-front-block-write"),
                // 다른 호스트는 영향 없음
                Arguments.of(HttpMethod.POST, "customer.posselect.com", "/api/login", "customer-front-block-write"));
    }

    @ParameterizedTest(name = "POST {0}{1} -> {2}")
    @MethodSource("loginRequests")
    void 로그인_POST_는_레이트리밋_라우트가_먼저_잡는다(String host, String path, String expectedRoute) {
        assertThat(firstMatchingRouteId(HttpMethod.POST, host, path))
                .as("POST %s%s", host, path)
                .isEqualTo(expectedRoute);
    }

    @ParameterizedTest(name = "{0} {1}{2} -> {3}")
    @MethodSource("otherRequests")
    void 로그인이_아닌_요청은_기존_라우트가_그대로_잡는다(HttpMethod method, String host, String path, String expectedRoute) {
        assertThat(firstMatchingRouteId(method, host, path))
                .as("%s %s%s", method, host, path)
                .isEqualTo(expectedRoute);
    }

    @Test
    void 로그인_라우트는_기존_api_라우트보다_앞에_있다() {
        List<String> ids = routeLocator.getRoutes().map(Route::getId).collectList().block();

        assertThat(ids).contains("admin-front-login", "admin-front-api", "partner-front-login", "partner-front-api");
        assertThat(ids.indexOf("admin-front-login"))
                .as("/api/** 를 통째로 받는 라우트보다 앞이어야 한다 - 뒤면 한도가 조용히 무력화된다")
                .isLessThan(ids.indexOf("admin-front-api"));
        assertThat(ids.indexOf("partner-front-login"))
                .as("/api/** 를 통째로 받는 라우트보다 앞이어야 한다 - 뒤면 한도가 조용히 무력화된다")
                .isLessThan(ids.indexOf("partner-front-api"));
    }

    @Test
    void 로그인_라우트는_기존_api_라우트와_같은_백엔드로_간다() {
        assertThat(definition("admin-front-login").getUri())
                .isEqualTo(definition("admin-front-api").getUri());
        assertThat(definition("partner-front-login").getUri())
                .isEqualTo(definition("partner-front-api").getUri());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("loginRouteIds")
    void 한도_숫자와_키_해석은_의도한_값으로_고정되어_있다(String routeId) {
        Map<String, String> args = definition(routeId).getFilters().stream()
                .filter(f -> "RequestRateLimiter".equals(f.getName()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(routeId + " 에 RequestRateLimiter 필터가 없다"))
                .getArgs();

        // 연속 5회(burstCapacity / requestedTokens), 이후 10초에 1회(replenishRate / requestedTokens)
        assertThat(args).containsEntry("redis-rate-limiter.replenishRate", "1");
        assertThat(args).containsEntry("redis-rate-limiter.requestedTokens", "10");
        assertThat(args).containsEntry("redis-rate-limiter.burstCapacity", "50");
        // 클라이언트 주소 기준. 사용자 키가 아닌 이유: 로그인 전이라 X-User-Id 가 없다
        assertThat(args).containsEntry("key-resolver", "#{@ipKeyResolver}");
    }

    static Stream<String> loginRouteIds() {
        return Stream.of("admin-front-login", "partner-front-login");
    }

    private RouteDefinition definition(String id) {
        return routeDefinitionLocator.getRouteDefinitions()
                .filter(d -> id.equals(d.getId()))
                .blockFirst();
    }
}
