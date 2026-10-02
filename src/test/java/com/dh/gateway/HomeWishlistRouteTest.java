package com.dh.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.gateway.route.Route;
import org.springframework.cloud.gateway.route.RouteLocator;

/**
 * home.posselect.com 의 상품 카드 찜 하트는 같은 출처의 {@code /api/wishlists/**} 를 호출한다(gateway#304).
 * 이 호스트는 {@code store-front-block-write} 가 쓰기 메서드를 전부 403 으로 끊으므로, 찜 라우트가
 * 그보다 <b>앞</b>에 있어야 POST/DELETE 가 product-api 에 닿는다. 순서가 뒤집혀도 GET 은 계속 되기
 * 때문에(차단 규칙은 쓰기만 본다) "조회는 되는데 찜이 안 눌린다"로만 드러난다 — 그래서 순서를 고정한다.
 */
@SpringBootTest
class HomeWishlistRouteTest {

    @Autowired
    private RouteLocator routeLocator;

    @Test
    void homeWishlistRouteComesBeforeTheWriteBlockAndTheCatchAllFrontRoute() {
        List<String> ids = routeLocator.getRoutes().map(Route::getId).collectList().block();

        assertThat(ids).contains("product-api-wishlist-home", "store-front-block-write", "store-front");
        assertThat(ids.indexOf("product-api-wishlist-home"))
                .as("쓰기 차단보다 앞이어야 POST/DELETE 가 403 으로 끊기지 않는다")
                .isLessThan(ids.indexOf("store-front-block-write"));
        assertThat(ids.indexOf("product-api-wishlist-home"))
                .as("Host 만 보는 store-front 라우트보다 앞이어야 Next.js 로 새지 않는다")
                .isLessThan(ids.indexOf("store-front"));
    }

    @Test
    void homeWishlistRoutePointsAtProductApi() {
        Route route = routeLocator.getRoutes()
                .filter(r -> "product-api-wishlist-home".equals(r.getId()))
                .blockFirst();

        assertThat(route).isNotNull();
        assertThat(route.getUri()).hasToString("http://product-api.customer.svc.cluster.local:8080");
    }
}
