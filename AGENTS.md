# gateway AI 개발 지침

> **캐논 참조**: 이 저장소의 공통 개발 원칙(DB/트랜잭션/보안/배포 규칙 등)은 `~/msa/AGENTS.md`를 우선 따른다. 아래는 이 저장소만의 특이사항이다.

# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

Spring Cloud Gateway (WebFlux/Netty) reverse proxy for the leedohyun.com service mesh. It sits in front of
several backend services (auth.api, customer-front, store-front, wordpress, minio, redmine, keycloak, etc.)
running in a K3s cluster, and is also expected to terminate JWT-based auth for the `customer.localhost`
domain before forwarding identity to backend services. The codebase is intentionally tiny: one global
filter class plus route config in YAML.

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

- `src/main/resources/application.yml` is the **production** config: host-predicate routes for
  `tool.leedohyun.com`, `leedohyun.com`/`www`/`blog`/`wordpress.leedohyun.com`, `static.leedohyun.com`
  (minio API), `minio.leedohyun.com` (minio console), `alm.leedohyun.com`/`redmine.leedohyun.com`,
  `keycloak.leedohyun.com`, and `architecture.leedohyun.com`. Each route explicitly re-sets
  `Host/Origin/Referer/Authorization/Cookie/X-Forwarded-For/X-Real-IP` from the incoming request via
  `SetRequestHeader` filters (added deliberately per git history — headers were being dropped otherwise).
  **Notably, this file has no routes for `auth-api`, `customer-front`, or `store-front`** — the
  auth/customer/home stack described below only has routes wired up under the `local` profile.
- `src/main/resources/application-local.yml` is the **local dev** config: host-predicate routes for
  `customer.localhost` (split into `/api/auth/**` → `auth-api:8080` and everything else →
  `customer-front:3000`), `home.localhost` → `store-front:3000`, and `auth.localhost` → `auth-api:8080`
  directly. When editing routing behavior for the customer/auth flow, this is the file that matters, and
  any change intended for production needs a corresponding route added to `application.yml` — it is not
  there today.

### JWT auth is a GlobalFilter, independent of routes

`src/main/java/com/dh/gateway/security/JwtAuthenticationFilter.java` implements `GlobalFilter` +
`Ordered` (order `HIGHEST_PRECEDENCE + 10`), so it runs on **every** request regardless of which route
(if any) matches — route YAML and auth enforcement are two separate, loosely-coupled mechanisms. Flow:

1. Bypass checks first (`isPublic`): host is `auth.localhost` or `home.localhost` (hardcoded literals —
   note these are `.localhost` names only, so this bypass does not match any production hostname such as
   `auth.leedohyun.com`); or path is exactly `/api/auth/login`, `/api/auth/signup`, `/api/auth/logout`;
   or path starts with `/login`, `/_next/`, `/favicon.ico`. Matching requests pass through untouched.
2. Otherwise, it reads the `ACCESS_TOKEN` cookie (not an `Authorization` header). Missing cookie →
   302 redirect to `http://home.localhost:8080/` (also a hardcoded local URL).
3. The cookie value is parsed as a `SignedJWT` (Nimbus JOSE+JWT). The signing key is resolved by `kid`
   from auth.api's JWKS endpoint, fetched via `WebClient` from `http://auth-api:8080/.well-known/jwks.json`
   (hardcoded Docker-network hostname, 3s timeout) and cached indefinitely in an in-memory
   `ConcurrentHashMap<kid, RSAKey>` — the cache is never invalidated, so a JWKS key rotation that reuses a
   `kid` would not be picked up.
4. RS256 signature is verified (`RSASSAVerifier`) and expiry is checked manually against
   `claims.getExpirationTime()`. Any failure (parse, signature, expiry, missing/unknown `kid`) is caught
   and treated the same as a missing cookie: redirect to home.
5. On success, the claims' `sub` and `role` become new request headers — **`X-User-Id`** and
   **`X-User-Role`** — added via `request.mutate()` before the request continues down the filter chain to
   whatever backend the route config sends it to.

This confirms the assumption held by the sibling `auth.api` repo: `auth.api`'s `GET /api/auth/me` trusts
`X-User-Id`/`X-User-Role` headers without independently re-verifying the JWT (see `auth.api`'s
`AuthController`), and this gateway is indeed the component that validates the RS256 JWT against
`auth.api`'s JWKS and injects those headers. The trust boundary is real, but scoped: it only applies where
this global filter's bypass rules don't exempt the request, and — per the production/local config gap
above — the routes that would carry `customer.localhost` traffic (and thus benefit from these headers)
are not present in the production route file today.

### Key implication for changes

Because routing (YAML) and identity injection (`JwtAuthenticationFilter`) are decoupled, adding a new
protected backend route requires two edits: the route itself in the appropriate `application*.yml`, and,
if it should be public, an entry in `PUBLIC_HOSTS`/`PUBLIC_EXACT_PATHS`/`PUBLIC_PATH_PREFIXES` in
`JwtAuthenticationFilter`. There is no per-route auth annotation or config — the bypass/enforcement logic
is one hardcoded list read on every request.

**This is a recurring bug source because the filter lives in this repo but the pages/routes that need
whitelisting are defined in other repos** (`auth.api`, `customer.front`, ...). A change made entirely
inside another repo can silently break in production because nobody edited this file. Concrete incident
(2026-08-02): `customer.front`'s email-verification link (`https://customer.leedohyun.com/verify`) was
added, and the *API* path `/api/auth/verify-email` was correctly whitelisted at the time — but the
*frontend page* path `/verify` itself was not, since `customer.leedohyun.com` is a `PROTECTED_HOSTS`
entry. Unauthenticated visitors (anyone clicking the email link before their first login) were silently
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

**Whenever `customer.front` (or any future frontend under a `PROTECTED_HOSTS` domain)
adds a new page meant to be reachable before login, this file's `PUBLIC_EXACT_PATHS`/`PUBLIC_PATH_PREFIXES`
must be checked/updated too — both the page route AND any API route it calls.**

