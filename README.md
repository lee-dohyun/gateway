# gateway
Spring Cloud Gateway 기반 API 게이트웨이

간단 설명
- 이 레포지토리는 Spring Cloud Gateway와 WebFlux(Netty)를 사용한 API 게이트웨이 구현입니다.

주요 기술
- Java 21 (Gradle toolchain)
- Spring Boot 3.4.7
- Spring Cloud 2024.0.1
- Spring Cloud Gateway, Spring WebFlux

프로젝트 구성
- 진입점: src/main/java/com/dh/gateway/GatewayApplication.java
- 라우트 설정: src/main/resources/application.yml
- 빌드 도구: Gradle (wrapper 포함)
- Docker: Dockerfile 포함

빠른 시작
1. 시스템 요구
	- Java 21 설치 또는 JDK 21이 사용 가능한 환경
	- Docker (선택)

2. 빌드
	- Linux / macOS: `./gradlew build`
	- Windows (PowerShell / CMD): `gradlew.bat build`

3. 실행
	- 개발 실행: `./gradlew bootRun` (Windows: `gradlew.bat bootRun`)
	- 또는 패키징 후 실행: `java -jar build/libs/gateway-0.0.1-SNAPSHOT.jar`

4. Docker (선택)
	- 이미지 빌드: `docker build -t gateway .`
	- 컨테이너 실행: `docker run -p 8080:8080 gateway`

설정
- 라우트와 필터는 `src/main/resources/application.yml`에서 정의되어 있습니다. 도메인(Host) 기반 프리디케이트와 여러 SetRequestHeader 필터가 예시로 포함되어 있습니다.

테스트
- 단위/통합 테스트 실행: `./gradlew test`

참고/라이선스
- 라이선스 파일은 `LICENSE`에 포함되어 있습니다.

문의
- 추가 설정(예: 환경별 application-*.yml, Keycloak 연동, CORS, 로깅)이나 도커/쿠버네티스 배포를 원하시면 알려주세요.
