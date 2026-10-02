package com.dh.gateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.http.HttpCookie;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.http.server.reactive.MockServerHttpRequest;
import org.springframework.mock.web.server.MockServerWebExchange;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ServerWebExchange;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/**
 * home.posselect.com 의 {@code /api/wishlists/**} 선택 인증(gateway#304).
 *
 * 이 호스트는 protected host 가 아니라 기본은 "검증 없이 통과"인데, product.api 의 WishlistController 는
 * {@code X-User-Id} 를 필수로 요구한다. 그래서 이 경로만 "쿠키가 있으면 검증해서 헤더 주입, 없으면 그대로
 * 통과"로 둔다. 실제 서명 검증까지 태우기 위해 테스트용 RSA 키를 필터의 JWKS 캐시에 직접 넣는다.
 */
class JwtAuthenticationFilterHomeWishlistTest {

    private static final String HOME_HOST = "home.posselect.com";
    private static final String ISSUER = "https://keycloak.posselect.com/realms/customer";
    private static final String KID = "test-kid";

    private JwtAuthenticationFilter filter;
    private GatewayFilterChain chain;
    private RSAKey rsaKey;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() throws Exception {
        GatewaySecurityProperties properties = new GatewaySecurityProperties();
        properties.setProtectedHosts(List.of("customer.posselect.com"));
        properties.setHomeHosts(List.of(HOME_HOST, "www.posselect.com"));
        properties.setLoginUrl("https://customer.posselect.com/login");
        properties.setKeycloakIssuer(ISSUER);
        filter = new JwtAuthenticationFilter(WebClient.builder(), properties);

        rsaKey = new RSAKeyGenerator(2048).keyID(KID).generate();
        ((Map<String, RSAKey>) ReflectionTestUtils.getField(filter, "keyCache")).put(KID, rsaKey.toPublicJWK());

        chain = mock(GatewayFilterChain.class);
        when(chain.filter(any())).thenReturn(Mono.empty());
    }

    @Test
    void 유효한_쿠키면_찜_경로에_사용자_헤더를_주입한다() throws Exception {
        HttpHeaders forwarded = forward(MockServerHttpRequest.post("https://" + HOME_HOST + "/api/wishlists?productId=1")
                .cookie(accessToken("user-sub-1")));

        assertThat(forwarded.getFirst("X-User-Id")).isEqualTo("user-sub-1");
    }

    @Test
    void 하위_경로도_같은_규칙을_탄다() throws Exception {
        HttpHeaders forwarded = forward(MockServerHttpRequest.get("https://" + HOME_HOST + "/api/wishlists/product-ids")
                .cookie(accessToken("user-sub-1")));

        assertThat(forwarded.getFirst("X-User-Id")).isEqualTo("user-sub-1");
    }

    @Test
    void 쿠키가_없으면_로그인으로_보내지_않고_헤더_없이_통과시키며_위조된_헤더는_지운다() {
        HttpHeaders forwarded = forward(MockServerHttpRequest.get("https://" + HOME_HOST + "/api/wishlists/product-ids")
                .header("X-User-Id", "forged-by-client"));

        assertThat(forwarded.containsKey("X-User-Id")).isFalse();
    }

    @Test
    void 찜이_아닌_경로는_유효한_쿠키가_있어도_헤더를_주입하지_않는다() throws Exception {
        // 이름이 비슷한 경로(/api/wishlists-export 등)까지 선택 인증이 번지지 않는지 본다.
        HttpHeaders forwarded = forward(MockServerHttpRequest.get("https://" + HOME_HOST + "/api/wishlists-export")
                .cookie(accessToken("user-sub-1")));

        assertThat(forwarded.containsKey("X-User-Id")).isFalse();
    }

    private HttpHeaders forward(MockServerHttpRequest.BaseBuilder<?> request) {
        MockServerWebExchange exchange = MockServerWebExchange.from(request);
        StepVerifier.create(filter.filter(exchange, chain)).verifyComplete();

        ArgumentCaptor<ServerWebExchange> captor = ArgumentCaptor.forClass(ServerWebExchange.class);
        verify(chain).filter(captor.capture());
        assertThat(exchange.getResponse().getStatusCode()).as("로그인 리다이렉트가 아니어야 한다").isNull();
        return captor.getValue().getRequest().getHeaders();
    }

    // MockServerHttpRequest 는 "Cookie" 헤더 문자열을 파싱하지 않는다 - getCookies() 에 실리려면 cookie() 로 넣어야 한다.
    private HttpCookie accessToken(String subject) throws Exception {
        return new HttpCookie("ACCESS_TOKEN", token(subject));
    }

    private String token(String subject) throws Exception {
        SignedJWT jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(KID).build(),
                new JWTClaimsSet.Builder()
                        .subject(subject)
                        .issuer(ISSUER)
                        .claim("email", "user@example.com")
                        .claim("name", "tester")
                        .expirationTime(new Date(System.currentTimeMillis() + 60_000))
                        .build());
        jwt.sign(new RSASSASigner(rsaKey));
        return jwt.serialize();
    }
}
