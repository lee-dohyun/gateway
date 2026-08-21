# gateway AI 개발 지침

> **캐논 참조**: 이 저장소의 공통 개발 원칙(DB/트랜잭션/보안/배포 규칙 등)은 `~/msa/AGENTS.md`를 우선 따른다. 아래는 이 저장소만의 특이사항이다.

# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

Spring Cloud Gateway (WebFlux/Netty) reverse proxy that is the **single entry point for every domain on
this cluster** — both the `leedohyun.com` family (tool, blog/wordpress, minio, architecture, router admin)
and the `posselect.com` shopping mall family (customer / home / www / product / admin / coffee fronts,
their APIs, keycloak, grafana, imgproxy/CDN, the design-system and shell static sites). Nothing reaches a
backend without passing through here.

It is also the **only** place JWT auth is enforced: `JwtAuthenticationFilter` validates the `ACCESS_TOKEN`
cookie against Keycloak and injects identity headers that downstream services trust without re-verifying.
The codebase is intentionally tiny — one global filter, one `@ConfigurationProperties` class, and route
config in YAML.

Redmine (`alm.leedohyun.com` / `redmine.leedohyun.com`) was decommissioned on 2026-08-17; its routes are
gone and must not be reintroduced.

## Commands

- Build: `./gradlew build` (Windows: `gradlew.bat build`)
- Run locally: `./gradlew bootRun --args='--spring.profiles.active=local'` (or set
  `SPRING_PROFILES_ACTIVE=local`) — the `local` profile (`application-local.yml`) points routes at
  `*.localhost` hostnames instead of the production K3s service names.
- Run packaged jar: `java -jar build/libs/gateway-0.0.1-SNAPSHOT.jar`
- Test (all): `./gradlew test`
- Test (single class): `./gradlew test --tests "com.dh.gateway.GatewayApplicationTests"`
- Test (single method): `./gradlew test --tests "com.dh.gateway.GatewayApplicationTests.contextLoads"`
- Docker: `docker build -t gateway .` then `docker run -p 8080:8080 gateway`
- CI (`.github/workflows/docker-image.yml`): on push to `main`, runs `./gradlew build`, then builds/pushes
  the Docker image to Docker Hub as `<project-name>:latest` (project name read from `settings.gradle`).
  There is no separate lint step or job matrix — `./gradlew build` (which runs `test`) is the only gate.

## Architecture

### Two route configs, not one

- `src/main/resources/application.yml` is the **production** config and holds every route. Read it before
  claiming anything about routing — it changes often. Broad shape as of 2026-08-21:
  - `leedohyun.com` family: `tool`, `blog`/`wordpress`, `static` (minio API), `minio` (console),
    `architecture`, `router` (ASUS router admin over HTTPS with a pinned CA, see below). `leedohyun.com`
    and `www` 302 to `architecture.leedohyun.com`.
  - **Legacy `*.leedohyun.com` shop hosts are 302 redirects to their `*.posselect.com` equivalents**
    (customer / home / product / admin / keycloak / monitoring), not proxies. `posselect.com` itself 302s
    to `www.posselect.com`.
  - `posselect.com` family: `customer` (auth-api, order-api, customer-front), `home`+`www` (auth-api,
    store-front), `product` (product-api, order-api, auth-api addresses, product-front), `admin`,
    `coffee`, `keycloak`, `monitoring` (grafana), `ui`+`storybook`, `shell`, `static`, `image`
    (cdn-alias for `/cdn/**`, imgproxy otherwise).
- **Route order is load-bearing.** Spring Cloud Gateway matches in declaration order, and three patterns
  in this file depend on it:
  1. API path routes (`/api/auth/**`, `/api/orders/**`, …) must stay **above** the catch-all front route
     for the same host, or the front swallows them.
  2. The `*-block-write` routes (`SetStatus=403` on POST/PUT/PATCH/DELETE) sit **between** the API routes
     and the front route. They are the 2026-08-14 mitigation for an external `POST /` leading to shell
     execution inside a Next.js container; the API routes above them keep login/orders working.
     `admin-front` is the exception — it handles its own `/api/**`, so it has an `admin-front-api`
     carve-out before its block-write route. Moving any of these breaks either security or the app.
  3. `cdn-alias` (`image.posselect.com` + `Path=/cdn/**`) must precede the imgproxy catch-all for the same
     host.
- Two routes carry a `PreserveHostHeader` filter for a specific, non-obvious reason, both documented inline:
  `customer-front` (its `/terms`, `/privacy` SSR rebuilds `https://<host>/api/agreements` from the incoming
  Host, and a rewritten Host makes it attempt TLS against a plaintext port 3000 → 500) and `monitoring`.
  Do not remove them as noise. Elsewhere the app relies on `server.forward-headers-strategy: framework`.
- `router-admin` proxies to `https://leedohyun.asuscomm.com:8443` with a pinned certificate:
  `spring.cloud.gateway.httpclient.ssl.trustedX509Certificates: classpath:router-admin-ca.pem`. That PEM
  lives in `src/main/resources/` and is part of the build — do not delete it as a stray file.
- `src/main/resources/application-local.yml` is the **local dev** profile: the same customer/home/auth
  stack pointed at `*.localhost` and Docker-network service names, plus local `gateway.security.*` values.
  It only covers that stack, so it is a small subset of production — a production routing change is not
  reflected here and does not need to be.

### JWT auth is a GlobalFilter, independent of routes

`src/main/java/com/dh/gateway/security/JwtAuthenticationFilter.java` implements `GlobalFilter` +
`Ordered` (order `HIGHEST_PRECEDENCE + 10`), so it runs on **every** request regardless of which route
(if any) matches — route YAML and auth enforcement are two separate, loosely-coupled mechanisms. Flow:

0. **Every request has `X-User-Id` / `X-User-Email` / `X-User-Role` / `X-User-Name` stripped first**, on
   every branch, before anything else happens (`stripUserIdentityHeaders`). These headers are the identity
   proof downstream services trust, so a client that simply sets them would otherwise impersonate anyone.
   That was a real vulnerability: until 2026-08-12 a request with a forged `X-User-Email` and no cookie
   could delete another user's account. **Any new branch through this filter must keep the strip.**
0b. Host/issuer/login values are **not hardcoded** — they come from `gateway.security.*` in
   `application*.yml` via `GatewaySecurityProperties` (`protected-hosts`, `optional-auth-hosts`,
   `home-hosts`, `login-url`, `keycloak-issuer`, `keycloak-realm-url`), each overridable by a `SHOP_*`
   env var. Adding a protected domain is a config change, not a code change.
1. `/api/auth/me` on a `home-hosts` or `protected-hosts` host, and every request to an
   `optional-auth-hosts` host (`product.posselect.com`), take the **optional** path: the cookie is verified
   if present and headers injected, but a missing or invalid cookie passes through unauthenticated instead
   of redirecting. This is what lets the shared Header render a logged-out state on `/login` itself, and
   lets anonymous users browse products and a cart.
2. Otherwise auth is required only when the host is in `protected-hosts` (production:
   `customer.posselect.com`) **and** the path is not in `PUBLIC_EXACT_PATHS` / `PUBLIC_PATH_PREFIXES`.
   Everything else — wordpress, minio, architecture, the other fronts — passes through untouched.
3. The `ACCESS_TOKEN` cookie (not an `Authorization` header) is parsed as a `SignedJWT` (Nimbus JOSE+JWT).
   The signing key is resolved by `kid` from **Keycloak's** JWKS
   (`{keycloak-realm-url}/protocol/openid-connect/certs`, 3s timeout) and cached indefinitely in an
   in-memory `ConcurrentHashMap<kid, RSAKey>` — the cache is never invalidated, so a key rotation that
   reuses a `kid` would not be picked up.
4. RS256 signature, expiry, **and the `iss` claim against `keycloak-issuer`** are all checked. Any failure
   is treated the same as a missing cookie: 302 to `login-url` with the original URL attached as
   `redirect_uri` so login can bounce the user back.
5. On success the request gains `X-User-Id` (Keycloak `sub`), `X-User-Email`, `X-User-Name`
   (URL-encoded — HTTP headers are ISO-8859-1 only and names are Korean), and `Authorization: Bearer
   <token>` for services that verify the token themselves.

**`X-User-Id` is the owner key, `X-User-Email` is for display only.** Email is user-changeable
(`PUT /api/auth/me`), so a downstream service that stores email as the owner of a row loses that row when
the user edits their address. This is written on the `withUserHeaders` javadoc for the same reason.

### Key implication for changes

Because routing (YAML) and identity enforcement (`JwtAuthenticationFilter`) are decoupled, adding a new
route under a protected host requires two edits: the route itself in `application.yml`, and — if it must
be reachable before login — an entry in `PUBLIC_EXACT_PATHS` / `PUBLIC_PATH_PREFIXES` in
`JwtAuthenticationFilter`. There is no per-route auth annotation; those two lists are read on every
request. (Which *hosts* are protected is config, but which *paths* are public is still code.)

**This is a recurring bug source because the filter lives in this repo but the pages/routes that need
whitelisting are defined in other repos** (`auth.api`, `customer.front`, ...). A change made entirely
inside another repo can silently break in production because nobody edited this file. Concrete incident
(2026-08-02): `customer.front`'s email-verification link (`https://customer.leedohyun.com/verify`) was
added, and the *API* path `/api/auth/verify-email` was correctly whitelisted at the time — but the
*frontend page* path `/verify` itself was not, since `customer.leedohyun.com` was the protected host at
the time (the shop has since moved to `customer.posselect.com`). Unauthenticated visitors (anyone clicking the email link before their first login) were silently
302-redirected to `home.leedohyun.com` with no error. Fixed by adding `/verify` to `PUBLIC_EXACT_PATHS`
(commit `0565a01`). Same bug again (2026-08-20): `customer.front`'s terms/privacy content was
unreachable logged-out because **all three** of `/api/agreements` (the signup modal's client-side
fetch), `/terms` and `/privacy` (standalone pages, whose SSR loops back out through this gateway to
`/api/agreements` on its own host) were missing from both lists — and the modal failed *silently*,
because `fetch` follows the 302 and receives the login page HTML as a **200**, so `res.ok` is true and
only the subsequent `res.json()` throws, straight into an empty `catch`. Fixed by adding
`/api/agreements` to `PUBLIC_EXACT_PATHS` and `/terms`, `/privacy` to `PUBLIC_PATH_PREFIXES`, plus
`JwtAuthenticationFilterPublicPathTest`, which now pins every pre-login path so the next omission
fails the build instead of production.

**Whenever `customer.front` (or any future frontend under a `protected-hosts` domain)
adds a new page meant to be reachable before login, this file's `PUBLIC_EXACT_PATHS`/`PUBLIC_PATH_PREFIXES`
must be checked/updated too — both the page route AND any API route it calls.**

## Claude Code wiring

- **`.claude/agents/gateway-route-guard.md`** — run this whenever a route changes here, or when a sibling
  repo adds a page/endpoint that must work before login. It exists specifically for the cross-repo gap
  described above.
- **`.claude/hooks/pre-push-verify.sh`** — a `PreToolUse` hook that runs `./gradlew test` before any
  `git push`. A push to `main` is a production deploy (the self-hosted runner does `kubectl set image`),
  and `JwtAuthenticationFilterPublicPathTest` is the thing standing between a missed whitelist entry and a
  silent production outage — so it must actually run. Override with `CLAUDE_SKIP_PUSH_VERIFY=1` only for a
  documented reason.
- **`.claude/settings.json`** — allowlists read-only gradle/git/kubectl commands and denies reading `.env`
  files and deployer kubeconfigs.

## This repo is the catch-all for cluster-wide issues

Infrastructure work that has no better home (netpol, TLS, monitoring, backups, cross-cutting incidents)
is tracked as GitHub issues **in this repo**, not as repo-less Draft cards on the project board.

---

<!-- canon:begin sha=7ea99a43c561 src=~/msa/AGENTS.md -->
## 공통 캐논 (모든 AI 도구 공통)

> **공통 캐논 (자동 주입 — 손으로 고치지 말 것).** 원본은 `~/msa/AGENTS.md`이고 이 블록은
> `~/msa/scripts/sync-agents-canon.sh`가 넣는다. 이 저장소만 클론해 도는 도구(Codex, CI,
> 워크스페이스를 저장소로만 연 IDE)는 `~/msa`를 볼 수 없으므로 규칙을 여기 함께 둔다.
> **규칙을 바꿀 때는 원본을 고치고 sync 스크립트를 다시 돌릴 것.**

### 현재 단계: 개발 단계 (운영 제약 유예)

**posselect는 아직 실사용자 트래픽이 없는 개발 단계다.** 사용자가 명시적으로 확인한 사항: 무중단 배포·롤링 안전성·하위 호환 유지 같은 운영 제약을 기본값으로 깔지 말고, 다운타임이 나거나 기존 데이터를 리셋해야 해도 **가장 단순한 방법으로 바로 변경·적용**한다.

- 아래 §3의 **expand-contract(2단계 제거) 규칙은 이 유예가 끝난 뒤 적용**한다. 개발 단계에서는 컬럼/테이블을 한 번에 갈아엎어도 된다. 단 **Flyway 마이그레이션으로만 바꾼다는 규칙 자체는 유예 대상이 아니다**(체크섬 사고 이력).
- 이 유예는 한시적이다. **실 서비스 시작 시점은 사용자가 별도로 통지**하며, 통지 이후에는 이 절을 삭제하고 §3을 그대로 적용한다.

## 3. 불변 개발 규칙 (위반 금지)

실제 사고에서 도출된 규칙이다. 근거 이슈를 함께 표기한다.

### DB / 스키마
- **스키마 변경은 Flyway 마이그레이션으로만.** `ddl-auto`는 `validate` 유지, `update` 복귀 금지 (posselect #104).
- 스키마 변경은 **expand-contract**: 컬럼/테이블 제거는 "새것 추가 → 코드 전환 → 다음 릴리스에서 제거" 2단계로.
- `@Enumerated(STRING)` enum에 값 추가 시 기존 CHECK 제약은 자동으로 안 넓혀짐 — 마이그레이션에 `ALTER` 포함할 것.
- 재고 음수 방지 CHECK, 멱등성 유니크 인덱스 등 **DB 레벨 제약은 애플리케이션 로직과 별개로 유지**한다 (posselect #211 V3).

### 트랜잭션 / 정합성
- **`@Transactional` 안에서 원격 HTTP 호출 금지**(보상 로직 없이). 로컬 롤백돼도 원격은 롤백 안 된다 (posselect #140, order.api 사례).
- **모든 상태 변경(쓰기) API는 멱등해야 한다.** 재시도/중복 호출이 이중 차감·이중 결제가 되지 않게 멱등성 키(예: orderId) 기반 dedup을 넣는다 (posselect #211).
- 클래스 레벨 `@Transactional(readOnly = true)`인 클래스에 쓰기 경로 추가 금지 — 전파 함정으로 UPDATE가 조용히 사라진다. 쓰기는 별도 클래스 또는 `REQUIRES_NEW` (posselect #211 롤백 사례).
- **트랜잭션 전파·멱등성 변경은 단위 테스트로 검증이 성립하지 않는다.** 실제 DB 상태 변화 실측(같은 키로 2회 호출 → 1회만 반영)으로 검증하고, 실측 후 데이터 원복까지 한 세트로 수행 (posselect #211).

### 보안 / 인가
- **사용자 식별 키는 Keycloak sub(`X-User-Id`)만.** 이메일은 변경 가능하므로 소유자 키로 쓰지 않는다 (posselect #210).
- 게이트웨이 주입 헤더(`X-User-*`)는 게이트웨이가 항상 **덮어써야** 한다 — 클라이언트가 보낸 값을 통과시키면 인증 우회가 된다 (msa #87).
- **리소스 조회/변경 API에는 소유자 검사 필수.** 소유자 불일치는 403이 아니라 **404**로 응답(순번 ID에서 403은 유효 ID 범위를 노출) (posselect #214).
- **새로 외부에 노출되는 리소스는 순번 PK(BIGSERIAL)를 URL/응답에 노출하지 말 것** — public_id(UUIDv7/ULID) 별도 부여 (posselect #214 재발 방지).
- 로그인 전 호출되는 경로를 추가하면 gateway `PUBLIC_EXACT_PATHS`에도 **반드시 같이** 등록 (라우팅과 인증 화이트리스트가 다른 저장소에 있음).
- 의존성 보안 패치(특히 Next.js/Spring)는 미루지 않는다 — store-front가 Next.js RCE(CVE-2025-66478)로 실제 침해 정황을 겪음 (msa #155).

### K8s / 배포
- stateful Deployment(PVC 사용)는 `strategy: Recreate`. 모든 PV는 `reclaimPolicy: Retain`. apply 전 `claimName`을 `kubectl get pvc`와 대조.
- 새 도메인은 기존 와일드카드 TLS 시크릿을 참조만 할 것 — Ingress에 `cert-manager.io/cluster-issuer` 어노테이션 추가 금지(와일드카드 인증서를 덮어쓰는 사고 이력).
- Ingress는 `leedohyun-com-ingress.yaml`/`posselect-com-ingress.yaml` 두 파일에 host만 추가. 서비스별 개별 Ingress 금지.
- CI는 main push → Docker 이미지 → CD(self-hosted runner) 즉시 프로덕션 반영. **문서만 바꿀 땐 커밋 메시지에 `[skip ci]`.**
- 여러 서비스에 걸친 변경은 **배포 순서**를 먼저 설계할 것(예: gateway → front → api 순서를 지켜야 게스트 결제가 안 끊기는 사례, posselect #210).
- `@posselect/ui` 변경은 Storybook만 자동 배포됨 — 소비 저장소 5개(customer/store/product/admin.front + posselect-shell)를 각각 재빌드해야 화면에 반영 (posselect #197).
- **`[skip ci]`는 커밋 제목뿐 아니라 본문에서도 인식된다.** 다른 커밋을 인용하려고 본문에 그 문자열을 적으면 배포가 조용히 건너뛰어진다 — 실제로 product.api 캐시 수정이 이 때문에 배포되지 않았다(gateway#204).
- **`[skip ci]`로 건너뛴 배포를 되살릴 때**: `docker-image.yml`에 `workflow_dispatch`만 추가하면 부족하다. `deploy` 잡의 `if:`가 `github.event_name == 'push'`로 고정돼 있어 수동 실행은 빌드만 하고 배포는 skip된다. 조건도 `push || workflow_dispatch`로 함께 풀 것(현재 product.api만 적용됨).
- **`pull_request` 워크플로는 PR head 브랜치의 파일로 돈다.** main의 워크플로를 고쳐도 이미 열려 있는 PR에는 반영되지 않고, `gh run rerun`은 원래 런의 워크플로 버전을 재사용한다. 수정 확인은 **브랜치를 리베이스한 뒤** 새 런으로 할 것.
- **Dependabot PR에는 저장소 시크릿이 전달되지 않는다.** 시크릿을 쓰는 스텝(`docker/login-action`)은 `if: github.event_name == 'push'`로 막고, `secrets.X`를 문자열에 끼워 넣는 곳(이미지 태그)은 `${{ secrets.X || 'ci-local' }}` 폴백을 줄 것 — 안 그러면 모든 Dependabot PR이 상시 실패해 PR 게이트 신호가 죽는다(gateway#209).

### CLI / 스크립팅
- **SSH를 통한 원격 bash 명령 실행 시 따옴표 이스케이프 주의:** PowerShell에서 변수(`$BODY`)를 따옴표 안에 넣어 원격 `curl` 등을 호출하면 bash 쪽에서 JSON 포맷 에러(`400 Bad Request` 등)가 발생하기 쉽다. 복잡한 인용부호(JSON 등)가 포함된 스크립트는 **전체를 Base64로 인코딩한 뒤 원격에서 디코딩하여 `bash`로 실행**한다 (`echo $b64 | base64 -d | bash`).

## 4. 작업 기록 및 관리 (GitHub & Memory) — 모든 도구 공통

모든 에이전트는 더 이상 Redmine을 사용하지 않으며, 아래의 **Task Execution Workflow**에 따라 GitHub Projects 및 Issues를 단일 소스(SSOT)로 활용합니다.

1. **명령 인식 (Command Recognition)**: 사용자의 의도와 작업 범위를 명확히 파악합니다.
2. **깃허브 이슈 확인 및 즉시 선점 (Check & Claim)**: 작업을 시작하기 전에 반드시 GitHub Project #2와 관련 저장소 이슈를 조회하여 동일/겹치는 작업이 이미 `In Progress`인지 확인합니다. 조회·클레임은 `~/msa/scripts/claim.sh <repo> <issue>` 한 줄로 수행한다(다른 세션이 잡고 있으면 스크립트가 막는다). 겹치는 항목이 없으면 **코드를 건드리기 전에** 해당 이슈를 만들거나 열어 Status를 `In Progress`로 즉시 전환합니다. **이 서버는 Claude Code/Codex/Antigravity 등 여러 AI 도구를 여러 세션으로 동시에 띄워 작업하는 환경이므로, "조회만 하고 착수 시점에 클레임하지 않는" 흐름으로는 다른 세션과 같은 소스/같은 작업이 겹칠 수 있다.** 조회 시 대상 항목이 이미 `In Progress`(특히 최근 갱신)이면 같은 작업을 새로 시작하지 말고 사용자에게 확인한다.
3. **작업 수행 (Task Execution)**: 파악된 작업을 순차적으로 수행하며 필요한 코드를 수정하거나 작성합니다.
4. **커밋 전 서브에이전트 검수 (Pre-commit Subagent Review)**: 코드를 커밋하기 전에 해당 레포지토리의 서브에이전트(또는 특화된 페르소나 규칙)를 활용하여 코드를 검수합니다.
5. **검수 후 주석 및 커밋 메시지 표준화 작성 (Standardized Comments & Commit Message)**: 검수가 완료된 코드에 대해 표준화된 주석을 달고, 일관된 양식의 커밋 메시지를 작성합니다.
6. **배포 (Deployment)**: 작성된 코드를 알맞은 파이프라인이나 환경으로 배포합니다.
7. **배포 후 정상 동작 확인 (Post-deployment Verification)**: 배포가 완료된 후 시스템이 정상적으로 동작하는지 반드시 테스트하고 검증합니다.

**지속적인 업데이트 (Continuous Updates)**: 위 과정을 진행하면서 진행 상황은 아래 §4-1 인계 프로토콜(`progress.sh`)로 이슈에 남깁니다. (예전 이 문단은 "내부 `task.md` 를 동기화하라"고 지시했으나, 그런 파일은 이 머신에 존재한 적이 없다 — 선언만 있고 실체가 없는 규칙이었으므로 제거했다.) 특히, **작업이 완전히 끝났을 때는 커밋 메시지(`Closes #이슈번호`)를 활용하거나 `gh issue close` 명령어를 통해 반드시 깃허브 이슈를 '완료(Closed)' 처리해야 합니다.**

**세션 격리 (Worktree, Check & Claim의 보완책)**: Check & Claim은 "같은 작업"의 중복 착수를 막는 조치이고, 이것과 별개로 여러 세션(도구 무관)이 **같은 저장소**(`~/git/<repo>`)의 공용 클론을 동시에 건드리면 서로 다른 작업이어도 파일/브랜치가 물리적으로 충돌할 수 있다. 저장소 작업을 시작할 때는 공용 클론을 직접 건드리기보다 별도 worktree를 기본으로 삼는다.
- Claude Code는 `EnterWorktree` 도구로 `.claude/worktrees/<repo>/<name>` 아래 자동 생성/전환한다 — 기본 경로를 그대로 쓴다.
- Codex/Antigravity 등 자체 worktree 기능이 없는 도구는 `git worktree add ../<repo>-<slug> -b <branch>`로 수동 생성하고, 작업 종료 후 `git worktree remove`로 정리한다.
- **각 저장소 `.gitignore`에 `.claude/worktrees/`가 반드시 있어야 한다.** 없으면 `git add -A`/`git add .` 한 번에 worktree 디렉터리 전체가 gitlink(모드 160000)로 커밋되어 origin까지 올라갈 수 있다 — 2026-08-21 `customer.front`에서 실제로 발생·이미 push된 상태로 확인됨(별도 정리 필요, 이 문서 편집만으로는 해결되지 않음).

## 4-1. 인계 프로토콜 — 다른 도구가 중간부터 이어받게 하기

세 도구(Claude Code / Codex / Antigravity)가 **전부 같은 GitHub 계정으로 커밋**하므로 assignee·커밋 author 로는 누가 무엇을 잡고 있는지 구분되지 않는다. 진행 상태를 공유할 수 있는 매체는 **이슈 코멘트 하나뿐**이다. 도구별 메모리(예: Claude의 `~/.claude/projects/.../memory`)나 로컬 파일에 적으면 다른 도구는 영원히 못 읽는다.

### 세션 시작 (도구 무관, 필수)

```bash
~/msa/scripts/session-start.sh      # 활성/스테일 클레임 + 저장소별 브랜치·미커밋·미푸시 상태
```

Claude Code 는 SessionStart 훅이 자동 실행한다(로컬 모드). **훅이 없는 도구는 세션의 첫 명령으로 직접 실행할 것.**

### 코멘트 규격 (기계 판독용 첫 줄 + 사람이 읽는 본문)

| 종류 | 언제 | 명령 |
|------|------|------|
| `CLAIM` | 코드를 건드리기 **전** | `~/msa/scripts/claim.sh <repo> <issue>` |
| `PROGRESS` | 의미 있는 단위마다 | `~/msa/scripts/progress.sh <repo> <issue> "한 일\|다음 단계\|검증 방법"` |
| `HANDOFF` | 중단하거나 끝낼 때 | `~/msa/scripts/handoff.sh <repo> <issue> "남은 일/위험" [--done]` |
| `TAKEOVER` | 남의 스테일 클레임을 인수할 때 | `~/msa/scripts/claim.sh <repo> <issue> --takeover` |

- 코멘트 첫 줄은 ```CLAIM tool=... branch=... started=...``` 형태로 고정된다. 손으로 쓰지 말고 스크립트를 쓸 것 — 포맷이 깨지면 다른 세션의 클레임 판정이 틀린다.
- **실행 도구 식별**: 스크립트가 환경변수로 자동 판별한다. Codex/Antigravity 처럼 판별이 안 되는 도구는 셸에 `export AGENT_TOOL=codex`(또는 `antigravity`)를 설정하거나 `--tool` 로 지정한다.

### 스테일 클레임 만료 (2시간)

마지막 프로토콜 코멘트가 **2시간**(`MSA_CLAIM_STALE_SECONDS`) 넘게 없으면 그 클레임은 만료된 것으로 보고 `--takeover` 로 인수할 수 있다. 반납되지 않은 `In Progress` 가 영원히 남아 다른 세션을 막는 문제를 이 규칙으로 푼다(2026-08-21 실측: In Progress 11건 중 클레임 기록이 있는 것 0건, 일부는 며칠째 정지).

### 인계 가능 = 원격에 push된 상태

로컬 worktree 의 브랜치는 다른 도구·다른 세션 눈에 **보이지 않는다.** 작업을 중단할 때는 `wip:` 커밋이라도 push 한 뒤 `handoff.sh` 를 실행한다(미푸시 상태로 인계하려 하면 스크립트가 막는다). `--done` 없이 실행하면 Status 는 `In Progress` 로 남고 클레임만 반납되어, 다른 도구가 `--takeover` 로 바로 이어받는다.

### 어디에 무엇을 쓰나

| 내용 | 위치 |
|------|------|
| 진행 중 상태·다음 단계·인계 정보 | **이슈 코멘트**(위 프로토콜) |
| 확정된 개발 규칙 | `~/msa/AGENTS.md` (이 문서) |
| 사고 기록·ADR 등 장기 지식 | GitHub Wiki(gateway/order.api) |
| 도구 자신의 작업 효율용 메모 | 각 도구의 메모리 — **다른 도구는 못 읽는다는 전제로만 사용** |

## 5-1. 자동 점검 장치 — 도구 무관 (2026-08-21 배선, 같은 날 도구 무관화)

규칙을 문서로만 선언하지 않고 실제로 강제하는 장치다. **어떤 AI 도구도 이 장치들을 우회하지 말 것** —
우회하면 이 문서의 규칙이 다시 선언으로만 남는다.

- **`<저장소>/scripts/verify.sh`** — push 전 검증의 **단일 진입점**. 스택을 자동 판별해
  `./gradlew test` 또는 `npm run typecheck/lint/test` 를 돌리고, `scripts/verify.d/*.sh` 추가 검사를 실행한다.
  문서·도구 설정만 바뀐 push 는 스스로 건너뛴다. 우회는 `MSA_SKIP_VERIFY=1`, 우회했다면 그 사실을 보고/이슈에 남길 것.
  - 호출자 3곳이 **같은 스크립트**를 부른다: `.githooks/pre-push`(도구 무관) / `.claude/hooks/pre-push-verify.sh`(Claude) / CI.
  - `.githooks/pre-push` 는 클론마다 `~/msa/scripts/bootstrap-hooks.sh` 를 1회 돌려 `core.hooksPath` 를 걸어야 활성화된다
    (이 설정은 커밋되지 않는 로컬 설정이다). **새 클론·새 머신에서 제일 먼저 할 일.**
  - 2026-08-21 이전에는 검증이 `.claude/hooks/` 아래에만 있어 Claude 이외의 도구가 push 하면 아무 검증도 걸리지 않았다.
- **`<저장소>/AGENTS.md` 의 `<!-- canon:begin -->` 블록** — 이 문서의 공통 규칙이 각 저장소에 주입된 사본이다.
  `~/msa` 는 git 저장소가 아니라 저장소만 클론해 도는 도구(Codex, CI, IDE)는 원본을 읽을 수 없기 때문이다.
  **손으로 고치지 말 것.** 규칙 변경은 이 문서를 고치고 `~/msa/scripts/sync-agents-canon.sh` 를 다시 돌린다
  (`--check` 로 어긋난 저장소를 찾는다). `CLAUDE.md`/`GEMINI.md` 는 `AGENTS.md` 심링크다.
- **`<저장소>/.claude/agents/*.md`** — 저장소별 가드(게이트웨이 화이트리스트, Flyway, 트랜잭션/멱등성,
  캐시 무효화, 디자인 토큰, 셸 계약). Claude Code는 자동 위임하고, **다른 도구는 해당 파일을 읽어 같은 점검을 수행할 것.**
- **결정적 검사 스크립트** — `check-token-mirror.sh`(posselect-ui), `check-i18n-keys.sh`/`check-mermaid.sh`
  (architecture), `~/msa/scripts/check-architecture-drift.sh`. LLM 없이 동작하므로 어떤 도구에서든 그냥 실행하면 된다.
- **CI** — 각 저장소 `pr-check.yml`(PR 단계 게이트), `claude-review.yml`(자동 리뷰, `ANTHROPIC_API_KEY` 필요).
  단 `pr-check.yml` 은 `pull_request` 에서만 돈다 — **main 직push 는 CI 게이트가 없고 곧 배포다.**
  그래서 push 전 검증은 `.githooks/pre-push` 가 유일한 방어선이다.

작업 기록은 `msa-work-log` 스킬(Claude Code) 또는 `~/.claude/skills/msa-work-log/SKILL.md`(다른 도구는 이 파일을
읽고 같은 절차 수행)를 따른다. **Project에 저장소 미연결 Draft issue를 만들지 말 것** — 2026-08-17 이관 때
중복 카드 210여 건이 생긴 원인이다. 항상 실제 저장소 Issue를 만들어 Project #2에 연결한다.
<!-- canon:end -->
