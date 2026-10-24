package com.dh.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.filter.ratelimit.RateLimiter;
import org.springframework.cloud.gateway.filter.ratelimit.RedisRateLimiter;
import org.springframework.cloud.gateway.route.RouteLocator;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * 로그인 경로 레이트리밋의 숫자가 <b>실제 Redis 에서</b> 의도대로 동작하는지 본다 (gateway#327).
 *
 * <p>{@link LoginRateLimitRouteTest} 는 YAML 에 적힌 숫자(replenishRate 1 / requestedTokens 10 / burstCapacity 50)
 * 를 고정할 뿐, 그 숫자가 "연속 5회, 이후 10초에 1회"가 되는지는 말해 주지 않는다. {@code requestedTokens} 를
 * 잘못 해석하면 burst 가 5 가 아니라 50 이 되는데 라우트 정의만 봐서는 안 보인다. 여기서는 운영과 같은 설정을
 * 읽은 {@link RedisRateLimiter} 로 진짜 Redis(Lua 스크립트)에 호출해서 확인한다.
 *
 * <p>{@code GatewayHttpIntegrationTest} 는 Redis 를 일부러 띄우지 않고 레이트리밋이 실패-허용되게 둔다 - 그쪽은
 * 요청이 백엔드에 무엇으로 도착하는지가 대상이라서다. 한도 자체는 이 테스트의 몫이다.
 */
@SpringBootTest
@Testcontainers
class LoginRateLimitRedisTest {

    private static final String ADMIN_LOGIN = "admin-front-login";
    private static final String PARTNER_LOGIN = "partner-front-login";

    @Container
    static GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);

    @DynamicPropertySource
    static void pointRedisAtContainer(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
    }

    @Autowired
    private RouteLocator routeLocator;

    @Autowired
    private RedisRateLimiter rateLimiter;

    @BeforeEach
    void buildRoutesSoTheLimiterKnowsEachRoutesConfig() {
        // 라우트가 만들어질 때 필터 인자가 레이트리미터에 등록된다. 등록 전에 isAllowed 를 부르면 기본값(1/1)으로 돈다.
        routeLocator.getRoutes().collectList().block();
    }

    private static String newClient() {
        return "client-" + UUID.randomUUID();
    }

    private RateLimiter.Response attempt(String routeId, String client) {
        return rateLimiter.isAllowed(routeId, client).block();
    }

    @Test
    void 연속_5회까지_허용되고_6번째부터_거부된다() {
        String client = newClient();

        for (int i = 1; i <= 5; i++) {
            assertThat(attempt(ADMIN_LOGIN, client).isAllowed()).as("%d번째 시도", i).isTrue();
        }
        assertThat(attempt(ADMIN_LOGIN, client).isAllowed()).as("6번째 시도").isFalse();
        assertThat(attempt(ADMIN_LOGIN, client).isAllowed()).as("7번째 시도").isFalse();
    }

    @Test
    void 응답_헤더가_운영_한도_숫자를_그대로_알려_준다() {
        RateLimiter.Response first = attempt(PARTNER_LOGIN, newClient());

        assertThat(first.getHeaders())
                .containsEntry("X-RateLimit-Replenish-Rate", "1")
                .containsEntry("X-RateLimit-Burst-Capacity", "50")
                .containsEntry("X-RateLimit-Requested-Tokens", "10")
                // 50 에서 한 번에 10 을 쓰니 첫 요청 뒤 40 이 남는다. -1 이면 Redis 오류로 한도가 꺼진 것이다.
                .containsEntry("X-RateLimit-Remaining", "40");
    }

    @Test
    void 클라이언트와_라우트는_서로_버킷을_공유하지_않는다() {
        String exhausted = newClient();
        for (int i = 0; i < 6; i++) {
            attempt(ADMIN_LOGIN, exhausted);
        }
        assertThat(attempt(ADMIN_LOGIN, exhausted).isAllowed()).as("소진된 클라이언트").isFalse();

        assertThat(attempt(ADMIN_LOGIN, newClient()).isAllowed())
                .as("같은 라우트의 다른 클라이언트").isTrue();
        assertThat(attempt(PARTNER_LOGIN, exhausted).isAllowed())
                .as("다른 라우트의 같은 클라이언트 - admin 에서 막혀도 partner 는 별개 버킷").isTrue();
    }

    @Test
    void 소진된_뒤_10초가_지나면_1회만_다시_허용된다() throws InterruptedException {
        String client = newClient();
        for (int i = 0; i < 5; i++) {
            attempt(ADMIN_LOGIN, client);
        }
        assertThat(attempt(ADMIN_LOGIN, client).isAllowed()).as("소진 직후").isFalse();

        // replenishRate 1 / requestedTokens 10 = 10초에 1회. 경계에서 흔들리지 않게 반 초 여유를 둔다.
        Thread.sleep(10_500);

        assertThat(attempt(ADMIN_LOGIN, client).isAllowed()).as("10.5초 뒤 첫 시도").isTrue();
        assertThat(attempt(ADMIN_LOGIN, client).isAllowed()).as("그 직후 두 번째 시도").isFalse();
    }
}
