package com.dh.gateway.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetSocketAddress;

import org.junit.jupiter.api.Test;
import org.springframework.cloud.gateway.filter.ratelimit.KeyResolver;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;

/**
 * 2026-08-25부터 product.posselect.com 카탈로그 API 전체가 500이었던 회귀(gateway#260)의 재현 테스트.
 *
 * getRemoteAddress()는 non-null이지만 getAddress()가 null인 unresolved 주소(프록시 경유 시 발생)에서
 * 기존 코드가 NPE를 던졌다 — getRemoteAddress() != null 체크만으로는 부족하다.
 */
class RateLimiterConfigTest {

    private final RateLimiterConfig config = new RateLimiterConfig();

    private static MockServerWebExchange exchangeWithUnresolvedRemoteAddress() {
        InetSocketAddress unresolved = InetSocketAddress.createUnresolved("proxy-internal-host", 54321);
        assertThat(unresolved.getAddress()).isNull();
        return MockServerWebExchange.from(
                MockServerHttpRequest.get("https://product.posselect.com/api/products")
                        .remoteAddress(unresolved));
    }

    @Test
    void ipKeyResolver는_unresolved_주소에서도_NPE_없이_키를_반환한다() {
        KeyResolver resolver = config.ipKeyResolver();

        String key = resolver.resolve(exchangeWithUnresolvedRemoteAddress()).block();

        assertThat(key).isEqualTo("proxy-internal-host");
    }

    @Test
    void userKeyResolver는_미로그인_요청이_unresolved_주소여도_NPE_없이_폴백한다() {
        KeyResolver resolver = config.userKeyResolver();

        String key = resolver.resolve(exchangeWithUnresolvedRemoteAddress()).block();

        assertThat(key).isEqualTo("ip_proxy-internal-host");
    }

    @Test
    void partnerKeyResolver는_파트너_헤더_없이_unresolved_주소여도_NPE_없이_폴백한다() {
        KeyResolver resolver = config.partnerKeyResolver();

        String key = resolver.resolve(exchangeWithUnresolvedRemoteAddress()).block();

        assertThat(key).isEqualTo("anonymous_partner_proxy-internal-host");
    }

    @Test
    void userKeyResolver는_정상_resolved_주소에서_기존_동작을_유지한다() {
        KeyResolver resolver = config.userKeyResolver();
        MockServerWebExchange exchange = MockServerWebExchange.from(
                MockServerHttpRequest.get("https://product.posselect.com/api/products")
                        .remoteAddress(new InetSocketAddress("127.0.0.1", 12345)));

        String key = resolver.resolve(exchange).block();

        assertThat(key).isEqualTo("ip_127.0.0.1");
    }
}
