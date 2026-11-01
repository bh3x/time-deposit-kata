# ADR-0010: Local runtime with Docker Compose

- **Status:** Accepted; partly superseded by [ADR-0013](0013-interest-periods.md)
- **Date:** 2026-11-01

## Context
Reviewers must be able to run the solution and "trigger the endpoints using the Swagger contract" (brief). ADR-0005
keeps the application free of documentation routes: Swagger UI runs as a separate container on its own origin. It
left two questions to this step: how that UI reaches the API, given the contract's relative server URL (`/api/v1`)
— a UI on another origin would call itself — and the CORS this then needs. ADR-0006 enables the demo seed through a
`demo` profile "which Docker Compose enables"; ADR-0007 names `docker compose up` as the command for the full stack.

Constraints: a reviewer may have Docker but no JDK 25; the application maps no route beyond the two operations
(ADR-0005); the PostgreSQL image matches the one the integration tests use (`postgres:18.6`, ADR-0009).

## Options considered
**How Swagger UI reaches the API**
1. **Absolute local server in the contract, CORS on the API** — add `http://localhost:8080/api/v1` before the
   relative server; the API allows cross-origin calls from the Swagger UI origin. A contract change and CORS
   configuration; keeps the UI a separate client, as ADR-0005 decided.
2. **Reverse proxy, one origin** — no CORS and no contract change, but the UI's routes share the API's origin;
   rejected in ADR-0005 (option 3).
3. **No UI container** — `openapi.yaml` loaded into Postman or Insomnia; weaker for "trigger via Swagger".

**CORS mechanism**
1. **One global mapping for `/api/**`** (`WebMvcConfigurer`), allowed origins from a property — one place, covers
   future operations, testable without the controller.
2. **`@CrossOrigin` on controllers** — spread across controllers; settings repeated per capability.

**Application image**
1. **Multi-stage Dockerfile** — builds the boot jar with the Gradle wrapper inside the image; only Docker needed;
   the first build downloads Gradle and dependencies.
2. **`bootBuildImage` (Buildpacks)** — no Dockerfile, but Gradle and a JDK must run on the host and the builder
   image is large.
3. **Jar built on the host, copied in** — fast image build, but needs a local JDK.

## Decision
- **Topology:** `java/compose.yaml` with three services: `postgres` (`postgres:18.6`, healthcheck `pg_isready`
  over TCP, since first-start init listens on the Unix socket only), `app` (port 8080, profile `demo`, datasource
  from `SPRING_DATASOURCE_*`, starts once `postgres` is healthy, restarts on failure) and `swagger-ui`
  (`swaggerapi/swagger-ui`, port 8081, serving the read-only `openapi.yaml`). Ports are published on `127.0.0.1`
  only, since the API has no authentication. No application healthcheck: the application has no health route
  (ADR-0005). Credentials are local-only defaults in the Compose file, not secrets.
- **Swagger UI to API: option 1.** The contract lists `http://localhost:8080/api/v1` first (Swagger UI's default
  server) and keeps `/api/v1` for same-origin clients. The generated base path stays `/api/v1`.
- **CORS: one global mapping** for `/api/**` in the `configuration` package (ADR-0008: `@Configuration` classes).
  - Allowed origins: property `app.cors.allowed-origins` (a list), default `http://localhost:8081`; a configured
    list replaces the default. A wildcard, an empty list or a blank entry fails at startup.
  - Allowed methods `GET`, `HEAD`, `POST`; allowed request headers `Idempotency-Key` and `Content-Type`; no exposed
    headers; no credentials (the contract has `security: []`); Spring's default max-age.
  - A request from another origin is rejected by Spring with `403` and a plain-text body. Assumption: like the
    container-level errors in ADR-0005, this is outside the contract; error handling is out of scope (brief).
  - CORS adds no route: preflight `OPTIONS` is framework-derived (ADR-0005), and `@CrossOrigin` is not used.
- **Image: multi-stage Dockerfile** — `./gradlew bootJar` on `eclipse-temurin:25.0.5_8-jdk-noble` (tests are not
  run in the image build; `./gradlew check` needs Docker itself), runtime on `eclipse-temurin:25.0.5_8-jre-noble`
  as a non-root user.

## Consequences
- One command for reviewers: `cd java && docker compose up --build`; Swagger UI at `http://localhost:8081`, API at
  `http://localhost:8080/api/v1`. The README explains this and the `curl` equivalents.
- The Redocly `no-server-example.com` warning on the `localhost` server is accepted: the server exists precisely
  for the local stack. The generated base path is unchanged (`/api/v1`).
- Ports are fixed: remapping the API port also needs a matching contract server, or a client other than Swagger UI.
  Another UI origin needs `app.cors.allowed-origins` (environment variable `APP_CORS_ALLOWEDORIGINS`).
- R3 (`docs/testing.md`) is covered: web slice for the CORS rules, end to end for the preflight with the real
  default. The Compose stack itself is verified manually; Testcontainers' Compose support is not used (ADR-0009).
- `./gradlew bootRun` still runs the application against an already running PostgreSQL (ADR-0007), with the
  datasource passed as `SPRING_DATASOURCE_*`.
