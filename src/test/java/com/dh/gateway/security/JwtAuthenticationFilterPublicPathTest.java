package com.dh.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.core.publisher.Mono;

/**
 * 로그인 전 접근이 목적인 경로가 protected-host에서 302로 막히지 않는지 검증한다.
 *
 * 이 필터의 화이트리스트는 여기(gateway)에 있는데 그 경로를 정의하는 페이지/라우트는 customer.front에
 * 있어서, 다른 레포에서만 작업하면 조용히 프로덕션이 깨지는 사고가 반복됐다(CLAUDE.md 재발 이력 참고).
 * 페이지 경로와 그 페이지가 호출하는 API 경로는 별개 항목이라 둘 다 검증한다.
 */
class JwtAuthenticationFilterPublicPathTest {

    private static final String PROTECTED_HOST = "customer.posselect.com";

    private JwtAuthenticationFilter filter;

    @BeforeEach
    void setUp() {
        GatewaySecurityProperties properties = new GatewaySecurityProperties();
        properties.setProtectedHosts(List.of(PROTECTED_HOST));
        properties.setLoginUrl("https://" + PROTECTED_HOST + "/login");
        filter = new JwtAuthenticationFilter(WebClient.builder(), properties);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/login", "/signup", "/find-id", "/find-password", "/reset-password", "/verify",
            // 회원가입 약관 모달이 로그인 전에 브라우저에서 직접 부르는 API
            "/api/agreements?type=terms",
            // 약관 페이지 본문(모달 밖 독립 URL) + 그 페이지가 SSR에서 자기 호스트로 다시 호출하는 API
            "/terms", "/privacy",
    })
    void 로그인_전_공개_경로는_쿠키가_없어도_통과한다(String path) {
        AtomicBoolean forwarded = new AtomicBoolean(false);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("https://" + PROTECTED_HOST + path));

        filter.filter(exchange, e -> {
            forwarded.set(true);
            return Mono.empty();
        }).block();

        assertThat(forwarded).isTrue();
        assertThat(exchange.getResponse().getStatusCode()).isNotEqualTo(HttpStatus.FOUND);
    }

    @ParameterizedTest
    @ValueSource(strings = { "/mypage", "/cart", "/api/orders" })
    void 보호_경로는_쿠키가_없으면_로그인으로_302된다(String path) {
        AtomicBoolean forwarded = new AtomicBoolean(false);
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("https://" + PROTECTED_HOST + path));

        filter.filter(exchange, e -> {
            forwarded.set(true);
            return Mono.empty();
        }).block();

        assertThat(forwarded).isFalse();
        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FOUND);
    }
}
