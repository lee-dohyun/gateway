package com.dh.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * application.yml 의 {@code trusted-proxies} 가 실제로 읽히고, Traefik 파드 주소는 믿되 그 밖은 믿지 않는지 본다.
 *
 * <p>이 값이 비면 게이트웨이가 X-Forwarded-* 를 떼어 버려 Keycloak·WordPress 가 내부 주소로 링크를 만든다
 * (gateway#247). 헤더가 실제로 전달되는지는 {@link GatewayHttpIntegrationTest} 가 보지만 그쪽은 루프백을 믿도록
 * 값을 바꿔 쓰므로, 운영 값 자체는 여기서 따로 본다.
 */
@SpringBootTest
class TrustedProxiesPropertyTest {

    @Value("${spring.cloud.gateway.server.webflux.trusted-proxies}")
    private String trustedProxies;

    @Test
    void 파드_대역은_믿고_그_밖의_주소는_믿지_않는다() {
        Pattern pattern = Pattern.compile(trustedProxies);

        assertThat(pattern.matcher("10.42.0.177").matches()).as("Traefik 파드").isTrue();
        assertThat(pattern.matcher("118.176.139.213").matches()).as("공인 주소").isFalse();
        assertThat(pattern.matcher("192.168.50.51").matches()).as("노드 주소").isFalse();
        assertThat(pattern.matcher("110.42.0.1").matches()).as("앞에 숫자가 붙은 주소").isFalse();
    }
}
