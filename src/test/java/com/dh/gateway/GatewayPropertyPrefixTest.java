package com.dh.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;

/**
 * 라우트가 새 접두사({@code spring.cloud.gateway.server.webflux})에만 있는지 본다 (gateway#314).
 *
 * <p>옛 접두사 {@code spring.cloud.gateway.routes} 는 Spring Cloud Gateway 4.3 에서 지원 종료 예고됐고 다음
 * 메이저에서 없어진다. 오래된 브랜치를 머지하다 라우트 블록이 옛 자리에 다시 생기면 지금은 조용히 동작하다가
 * 다음 상향 때 그 라우트만 사라진다.
 */
@SpringBootTest
class GatewayPropertyPrefixTest {

    @Autowired
    private Environment environment;

    @Test
    void 라우트는_새_접두사에만_있다() {
        assertThat(environment.getProperty("spring.cloud.gateway.server.webflux.routes[0].id")).isNotNull();
        assertThat(environment.getProperty("spring.cloud.gateway.routes[0].id"))
                .as("옛 접두사에 라우트를 추가하지 말 것 - application.yml 의 server.webflux.routes 아래에 둔다")
                .isNull();
    }
}
