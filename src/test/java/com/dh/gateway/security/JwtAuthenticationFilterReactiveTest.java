package com.dh.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpStatus;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.web.reactive.function.client.WebClient;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * {@link JwtAuthenticationFilter}를 StepVerifier로 검증하는 테스트.
 *
 * 기존 {@link JwtAuthenticationFilterPublicPathTest}는 쿠키가 아예 없는 경우(공개 경로 통과/보호
 * 경로 302)만 다루고 {@code .block()}으로 동기 처리한다. 여기서는 쿠키는 있지만 서명 검증에 실패하는
 * 경로(파싱 자체가 안 되는 값, verify()의 onErrorResume 분기)를 필터가 반환하는 Mono<Void> 자체를
 * StepVerifier로 구독해 검증하고, 응답 상태는 구독 완료 후에 확인한다. GatewayFilterChain은
 * Mockito 목으로 대체해 체인 자체는 실행되지 않게 한다.
 */
class JwtAuthenticationFilterReactiveTest {

    private static final String PROTECTED_HOST = "customer.posselect.com";

    private JwtAuthenticationFilter filter;
    private GatewayFilterChain chain;

    @BeforeEach
    void setUp() {
        GatewaySecurityProperties properties = new GatewaySecurityProperties();
        properties.setProtectedHosts(List.of(PROTECTED_HOST));
        properties.setLoginUrl("https://" + PROTECTED_HOST + "/login");
        properties.setKeycloakIssuer("https://keycloak.posselect.com/realms/customer");
        filter = new JwtAuthenticationFilter(WebClient.builder(), properties);
        chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());
    }

    @Test
    void 보호_경로에서_형식이_깨진_JWT_쿠키는_체인을_타지_않고_로그인으로_302된다() {
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("https://" + PROTECTED_HOST + "/mypage")
                        .header("Cookie", "ACCESS_TOKEN=not-a-valid-jwt"));

        StepVerifier.create(filter.filter(exchange, chain))
                .verifyComplete();

        assertThat(exchange.getResponse().getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(exchange.getResponse().getHeaders().getLocation())
                .hasToString("https://" + PROTECTED_HOST + "/login?redirect_uri="
                        + "https%3A%2F%2F" + PROTECTED_HOST + "%2Fmypage");
        verify(chain, never()).filter(any());
    }
}
