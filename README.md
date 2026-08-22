# gateway

leedohyun.com / posselect.com 클러스터의 **단일 진입점**. Spring Cloud Gateway(WebFlux/Netty) 기반.

## 역할

- **모든 도메인이 여기를 지난다.** `leedohyun.com` 계열(tool, blog/wordpress, minio, architecture,
  router 관리 UI)과 `posselect.com` 쇼핑몰 계열(customer / home / www / product / admin 프론트와
  각 API, keycloak, grafana, imgproxy·CDN, 디자인 시스템/셸 정적 사이트)을 호스트 프리디케이트로 분기한다.
  구 `*.leedohyun.com` 쇼핑몰 호스트는 프록시가 아니라 `*.posselect.com`으로 302 리다이렉트한다.
- **인증은 이 저장소에서만 강제된다.** `JwtAuthenticationFilter`(GlobalFilter)가 `ACCESS_TOKEN` 쿠키의
  JWT를 Keycloak(customer realm) 공개키로 검증하고, 성공 시 `X-User-Id` / `X-User-Email` / `X-User-Name`
  헤더를 주입한다. 하위 서비스는 이 헤더를 재검증 없이 신뢰하므로, 필터는 **어떤 분기를 타든 들어온
  요청의 해당 헤더를 먼저 제거한다**(헤더 위조로 타인 계정을 삭제할 수 있었던 2026-08-12 취약점 대응).
- 보호 대상 호스트·issuer·로그인 URL은 코드가 아니라 `gateway.security.*` 설정
  (`GatewaySecurityProperties`, `SHOP_*` 환경변수로 오버라이드)에서 온다.

## 구성

| 경로 | 내용 |
|---|---|
| `src/main/resources/application.yml` | 프로덕션 라우트 전체 + `gateway.security.*` 기본값. **라우트 선언 순서가 동작에 영향을 준다**(API 경로 → 쓰기 차단 → 프론트 catch-all) |
| `src/main/resources/application-local.yml` | `local` 프로파일. customer/home/auth 스택만 `*.localhost`로 |
| `src/main/resources/router-admin-ca.pem` | `router.leedohyun.com` 프록시가 신뢰할 사설 CA. `classpath:`로 참조되는 빌드 리소스 |
| `src/main/java/com/dh/gateway/security/` | `JwtAuthenticationFilter`, `GatewaySecurityProperties` |
| `src/test/.../JwtAuthenticationFilterPublicPathTest.java` | 로그인 전 접근 가능해야 하는 경로를 전부 고정. 화이트리스트 누락이 프로덕션이 아니라 빌드에서 터지게 한다 |

## 기술

Java 21(Gradle toolchain) / Spring Boot 3.4.7 / Spring Cloud 2024.0.1 / Spring Cloud Gateway + WebFlux /
Nimbus JOSE+JWT. Actuator `health`·`prometheus` 노출.

## 빌드·실행

```bash
./gradlew build                                              # test 포함
./gradlew bootRun --args='--spring.profiles.active=local'    # 로컬 프로파일
java -jar build/libs/gateway-0.0.1-SNAPSHOT.jar
docker build -t gateway . && docker run -p 8080:8080 gateway
```

## 배포

`.github/workflows/docker-image.yml` — `main` push 시 `./gradlew build` → Docker Hub 푸시 →
self-hosted runner가 K3s 이미지 교체까지 수행한다. **즉 main에 push하면 그대로 프로덕션에 나간다.**
`./gradlew build`(test 포함)가 유일한 게이트이므로 push 전에 반드시 로컬에서 테스트를 통과시킬 것
(`.claude/hooks/pre-push-verify.sh`가 이를 강제한다).

## 더 읽을 것

- AI 에이전트/기여자용 상세 지침과 함정 목록: [`CLAUDE.md`](CLAUDE.md) (= `AGENTS.md`)
- 세부 작업/설계 문서: [`docs/`](docs/)
- 라이선스: [`LICENSE`](LICENSE)
