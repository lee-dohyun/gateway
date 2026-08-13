package com.dh.gateway.security;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JwtAuthenticationFilter가 쓰는 도메인 관련 값들을 코드가 아니라 설정(application*.yml)에서
 * 관리한다. 쇼핑몰이 다른 도메인으로 또 옮기거나, 인증이 필요한 도메인 패밀리가 늘어날 때 이 클래스를
 * 고칠 필요 없이 property 값만 바꾸면 되도록 하기 위함.
 */
@ConfigurationProperties(prefix = "gateway.security")
public class GatewaySecurityProperties {

    /** ACCESS_TOKEN JWT의 iss 클레임과 비교할 기대 issuer. */
    private String keycloakIssuer;

    /** ACCESS_TOKEN 쿠키 검증을 강제하는 호스트 목록. */
    private List<String> protectedHosts = List.of();

    /** 로그인을 강제하진 않지만, 쿠키가 있으면 검증해서 사용자 헤더를 주입하는 호스트 목록. */
    private List<String> optionalAuthHosts = List.of();

    /** 로그인 상태 조회(/api/auth/me)를 강제 인증 없이 통과시키는 공개 랜딩 호스트. */
    private String homeHost;

    /**
     * 인증 실패 시 리다이렉트할 로그인 페이지 URL. 원래 가려던 경로는 redirect_uri 쿼리파라미터로
     * 붙여서 로그인 성공 후 그 경로로 돌아갈 수 있게 한다(customer.front 로그인 페이지가 이미 지원).
     */
    private String loginUrl;

    /** Keycloak realm JWKS 등을 조회할 클러스터 내부 URL. */
    private String keycloakRealmUrl;

    public String getKeycloakIssuer() {
        return keycloakIssuer;
    }

    public void setKeycloakIssuer(String keycloakIssuer) {
        this.keycloakIssuer = keycloakIssuer;
    }

    public List<String> getProtectedHosts() {
        return protectedHosts;
    }

    public void setProtectedHosts(List<String> protectedHosts) {
        this.protectedHosts = protectedHosts;
    }

    public List<String> getOptionalAuthHosts() {
        return optionalAuthHosts;
    }

    public void setOptionalAuthHosts(List<String> optionalAuthHosts) {
        this.optionalAuthHosts = optionalAuthHosts;
    }

    public String getHomeHost() {
        return homeHost;
    }

    public void setHomeHost(String homeHost) {
        this.homeHost = homeHost;
    }

    public String getLoginUrl() {
        return loginUrl;
    }

    public void setLoginUrl(String loginUrl) {
        this.loginUrl = loginUrl;
    }

    public String getKeycloakRealmUrl() {
        return keycloakRealmUrl;
    }

    public void setKeycloakRealmUrl(String keycloakRealmUrl) {
        this.keycloakRealmUrl = keycloakRealmUrl;
    }
}
