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

