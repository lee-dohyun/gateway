package com.dh.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * 공개 Keycloak 호스트에서 서버 간 호출 전용 경로를 막는 라우트(gateway#327)를 고정한다.
 *
 * <p>관리 콘솔·관리 REST, master/staff/partner realm, 토큰 엔드포인트는 서버끼리(auth.api, admin.front,
 * partner.front, 백엔드 JWKS 조회)만 쓰고, 그 호출은 전부 클러스터 내부 주소
 * (keycloak-service.keycloak.svc)로 간다 — 게이트웨이를 거치지 않으므로 여기서 막아도 영향이 없다.
 * 브라우저가 공개 호스트에서 쓰는 건 customer realm 의 소셜 로그인 경로뿐이라 그것만 열어 둔다.
 *
 * <p>테스트는 라우트 id 만 보지 않고 실제 predicate 를 요청에 적용해 "어느 라우트가 먼저 잡는가"를 본다.
 * 순서가 뒤집히면(차단 라우트가 {@code keycloak} 뒤로 밀리면) 조용히 전부 열린다.
 *
 * <p>percent-encoding({@code %6D}) 과 path parameter({@code ;x=1}) 변형은 Keycloak 이 그대로 해석해
 * 200 을 주는 것을 2026-10-24 실측으로 확인했다 — 게이트웨이 PathPattern 이 같은 방식으로 해석해
 * 막는지 여기서 고정한다. {@code ..} / {@code //} / {@code /./} 는 Traefik(sanitizePath 기본값)이
 * 게이트웨이 앞에서 정리하므로 이 테스트의 대상이 아니다 — 배포 후 curl 로 확인한다.
 */
@SpringBootTest
class KeycloakPublicPathRouteTest {

    private static final String BLOCK_ROUTE = "keycloak-internal-only-block";
    private static final String PASS_ROUTE = "keycloak";
    private static final String HOST = "keycloak.posselect.com";

    @Autowired
    private RouteLocator routeLocator;

    private String firstMatchingRouteId(HttpMethod method, String rawPath) {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.method(method, URI.create("https://" + HOST + rawPath))
                        .header(HttpHeaders.HOST, HOST));
        return routeLocator.getRoutes()
                .filterWhen(route -> Mono.from(route.getPredicate().apply(exchange)))
                .map(Route::getId)
                .blockFirst();
    }

    static Stream<Arguments> blocked() {
        return Stream.of(
                // 관리 콘솔 + 관리 REST (모든 realm)
                Arguments.of(HttpMethod.GET, "/admin"),
                Arguments.of(HttpMethod.GET, "/admin/master/console/"),
                Arguments.of(HttpMethod.GET, "/admin/realms"),
                Arguments.of(HttpMethod.POST, "/admin/realms/customer/users"),
                // 브라우저 흐름이 없는 realm 은 discovery·인증·계정 페이지까지 전부
                Arguments.of(HttpMethod.GET, "/realms/master"),
                Arguments.of(HttpMethod.GET, "/realms/master/.well-known/openid-configuration"),
                Arguments.of(HttpMethod.GET, "/realms/master/protocol/openid-connect/auth"),
                Arguments.of(HttpMethod.GET, "/realms/staff"),
                Arguments.of(HttpMethod.GET, "/realms/staff/account/"),
                Arguments.of(HttpMethod.GET, "/realms/staff/protocol/openid-connect/certs"),
                Arguments.of(HttpMethod.GET, "/realms/partner"),
                Arguments.of(HttpMethod.HEAD, "/realms/partner"),
                Arguments.of(HttpMethod.POST, "/realms/partner/protocol/openid-connect/token"),
                // 토큰 엔드포인트는 customer realm 포함 어느 realm 이든 공개 호스트로는 막는다
                Arguments.of(HttpMethod.POST, "/realms/staff/protocol/openid-connect/token"),
                Arguments.of(HttpMethod.POST, "/realms/customer/protocol/openid-connect/token"),
                Arguments.of(HttpMethod.POST, "/realms/customer/protocol/openid-connect/token/introspect"),
                Arguments.of(HttpMethod.POST, "/realms/customer/protocol/openid-connect/token/revoke"),
                // 클라이언트 동적 등록
                Arguments.of(HttpMethod.POST, "/realms/customer/clients-registrations/default"),
                Arguments.of(HttpMethod.POST, "/realms/customer/clients-registrations/openid-connect"),
                // Keycloak 이 해석해 주는 변형 — 2026-10-24 실측으로 master realm 이 200 으로 열렸다
                Arguments.of(HttpMethod.GET, "/realms/%6Daster/.well-known/openid-configuration"),
                Arguments.of(HttpMethod.GET, "/realms/master;x=1/.well-known/openid-configuration"),
                Arguments.of(HttpMethod.GET, "/admin;x=1/master/console/"));
    }

    static Stream<Arguments> open() {
        return Stream.of(
                // 소셜 로그인: customer.front 가 브라우저를 여기로 보낸다
                Arguments.of(HttpMethod.GET, "/realms/customer/protocol/openid-connect/auth"),
                Arguments.of(HttpMethod.GET, "/realms/customer/login-actions/authenticate"),
                Arguments.of(HttpMethod.POST, "/realms/customer/login-actions/authenticate"),
                Arguments.of(HttpMethod.GET, "/realms/customer/login-actions/first-broker-login"),
                // 구글이 돌려보내는 콜백
                Arguments.of(HttpMethod.GET, "/realms/customer/broker/google/endpoint"),
                Arguments.of(HttpMethod.POST, "/realms/customer/broker/google/endpoint"),
                // 로그인 화면 테마 리소스, 공개 메타데이터
                Arguments.of(HttpMethod.GET, "/resources/abc123/login/posselect/css/login.css"),
                Arguments.of(HttpMethod.GET, "/realms/customer/.well-known/openid-configuration"),
                Arguments.of(HttpMethod.GET, "/realms/customer/protocol/openid-connect/certs"),
                Arguments.of(HttpMethod.GET, "/realms/customer"),
                Arguments.of(HttpMethod.GET, "/robots.txt"));
    }

    @ParameterizedTest(name = "{0} {1} -> 차단")
    @MethodSource("blocked")
    void 서버간_전용_경로는_차단_라우트가_먼저_잡는다(HttpMethod method, String path) {
        assertThat(firstMatchingRouteId(method, path))
                .as("%s %s", method, path)
                .isEqualTo(BLOCK_ROUTE);
    }

    @ParameterizedTest(name = "{0} {1} -> 통과")
    @MethodSource("open")
    void 소셜_로그인에_필요한_경로는_keycloak_라우트로_간다(HttpMethod method, String path) {
        assertThat(firstMatchingRouteId(method, path))
                .as("%s %s", method, path)
                .isEqualTo(PASS_ROUTE);
    }

    @Test
    void 차단_라우트는_keycloak_라우트보다_앞이고_404_를_돌려준다() {
        List<Route> routes = routeLocator.getRoutes().collectList().block();
        List<String> ids = routes.stream().map(Route::getId).toList();

        assertThat(ids).contains(BLOCK_ROUTE, PASS_ROUTE);
        assertThat(ids.indexOf(BLOCK_ROUTE))
                .as("Host 만 보는 keycloak 라우트보다 앞이어야 한다 — 뒤면 차단이 조용히 무력화된다")
                .isLessThan(ids.indexOf(PASS_ROUTE));

        Route block = routes.get(ids.indexOf(BLOCK_ROUTE));
        // 백엔드로 보내지 않는 라우트(no://op) + SetStatus 필터. 404 인 이유: 존재 자체를 알리지 않는다.
        assertThat(block.getUri()).hasToString("no://op");
        assertThat(block.getFilters()).hasSize(1);
    }

    @Test
    void 다른_호스트는_이_차단의_영향을_받지_않는다() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("https://customer.posselect.com/admin/anything")
                        .header(HttpHeaders.HOST, "customer.posselect.com"));

        String id = routeLocator.getRoutes()
                .filterWhen(route -> Mono.from(route.getPredicate().apply(exchange)))
                .map(Route::getId)
                .blockFirst();

        assertThat(id).isNotEqualTo(BLOCK_ROUTE);
    }
}
