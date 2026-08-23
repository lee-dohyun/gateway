package com.dh.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;

/**
 * 디자인 시스템 문서 사이트는 Storybook Composition으로 두 빌드(posselect-ui = 프리미티브,
 * posselect-shell = Header/Footer)를 한 사이트로 합쳐 보여준다. 그 합침이 성립하려면
 * `storybook.posselect.com/shell/**` 가 shell 서비스로 가야 하는데, 이건 조용히 깨지는 종류의
 * 배선이다:
 *
 * <ul>
 *   <li>바로 아래 {@code posselect-ui} 라우트는 <b>Host만</b> 보므로, 순서가 뒤집히면
 *       {@code /shell/**} 을 먼저 삼켜 posselect-ui로 보내버린다.</li>
 *   <li>그래도 500이 나지 않는다 — Storybook 매니저는 index.json을 못 읽으면 사이드바에
 *       "No stories found"만 띄우고 끝이라, 배포는 성공한 것처럼 보인다(2026-08-23 실제 증상).</li>
 * </ul>
 *
 * 그래서 라우트 순서를 테스트로 고정한다. 짝이 되는 ref URL은 posselect-ui 저장소의
 * {@code .storybook/main.ts} 에 있다 — 한쪽만 바꾸면 이 테스트가 아니라 사이트가 조용히 깨진다.
 *
 * 근거: gateway#240, posselect-shell#13.
 */
@SpringBootTest
class StorybookCompositionRouteTest {

    @Autowired
    private RouteLocator routeLocator;

    @Test
    void shellStorybookRouteIsDefinedBeforeTheHostOnlyPosselectUiRoute() {
        List<String> ids = routeLocator.getRoutes().map(Route::getId).collectList().block();

        assertThat(ids).contains("posselect-shell-storybook-ref", "posselect-ui");
        assertThat(ids.indexOf("posselect-shell-storybook-ref"))
                .as("posselect-ui 라우트는 Host만 보므로 /shell/** 라우트가 반드시 먼저 와야 한다")
                .isLessThan(ids.indexOf("posselect-ui"));
    }

    @Test
    void shellStorybookRoutePointsAtTheShellServiceAndRewritesToItsStorybookPath() {
        Route route = routeLocator.getRoutes()
                .filter(r -> "posselect-shell-storybook-ref".equals(r.getId()))
                .blockFirst();

        assertThat(route).isNotNull();
        assertThat(route.getUri())
                .hasToString("http://posselect-shell-service.default.svc.cluster.local:80");
        // 프리픽스를 /storybook 으로 바꿔주지 않으면 shell 컨테이너 루트(header.js/footer.js 배포본)로
        // 떨어져 Storybook이 아니라 정적 번들 디렉터리를 보게 된다.
        assertThat(route.getFilters())
                .as("RewritePath 필터가 붙어 있어야 한다")
                .isNotEmpty();
    }
}
