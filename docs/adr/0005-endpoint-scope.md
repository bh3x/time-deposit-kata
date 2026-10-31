# ADR-0005: Endpoint scope

- **Status:** Accepted
- **Date:** 2026-10-31

## Context
The brief requires "**exactly two API endpoints**. Do not develop additional endpoints." and asks for instructions
on how to "trigger the endpoints using the Swagger contract". ADR-0004 defines the two operations
(`GET /api/v1/time-deposits`, `POST /api/v1/balance-updates`).

"Endpoint" can be read two ways:
1. a business operation of the API, as defined in `openapi.yaml`;
2. any HTTP route the running application serves, including documentation (Swagger UI, `/v3/api-docs`),
   monitoring (actuator) and framework routes (Spring Boot's `/error`).

A reviewer may apply either reading, for example by counting the routes of the running application. The tooling
and REST steps need this decided before adding dependencies such as springdoc or actuator.

This ADR refines two accepted ADRs without superseding them: ADR-0002 (the contract is served by a separate Swagger
UI container, not by the application) and ADR-0003 (the error fallback, see Consequences).

## Options considered
1. **Strict: the application serves only the two operations.** No springdoc, no actuator, no reachable `/error`
   route. Swagger UI runs as a separate container (Docker Compose) on the contract. Satisfies both readings and
   still gives an interactive Swagger UI; costs CORS configuration and one extra service.
2. **Strict, no bundled UI.** As option 1, but no Swagger UI container: the README explains loading
   `openapi.yaml` into an external tool (Swagger Editor, Postman, Insomnia) and calling the API with `curl`.
   Satisfies reading 2 for the whole stack; reviewers must set up a tool themselves, and browser-based tools need
   the same CORS configuration.
3. **Strict, UI and API behind one origin.** As option 1, but a reverse proxy serves Swagger UI and the API from
   the same origin. No CORS and no change to the contract's relative server URL; under reading 2 the shared origin
   then serves the UI's routes as well.
4. **Operations only: documentation routes allowed.** springdoc serves Swagger UI and `/v3/api-docs` from the
   application. Convenient; under reading 2 the application serves more than two endpoints.
5. **Option 4 plus actuator `/health`.** Adds a health check for Compose; a further route under reading 2.
6. **Documentation and health routes only under a `dev` profile.** Off by default; two configurations to run and
   explain, and a reviewer may still start the `dev` profile.

## Decision
**Option 1.** The Spring Boot application serves exactly the two operations of `openapi.yaml`, so the requirement
holds under both readings. Option 2 meets reading 2 equally well but makes triggering the endpoints harder for
reviewers; option 1 gives them a ready Swagger UI without adding routes to the application (CORS configuration
and a server URL for the UI remain, see Consequences). Option 3 avoids CORS but puts the UI's routes on the API's
origin.

- **Scope of reading 2:** applied to the Spring Boot application, the deliverable that implements the API.
  Assumption: the Swagger UI container is a separate client tool on its own origin, comparable to Swagger Editor
  or Postman, not part of the API.
- **Route:** a path and HTTP method the application maps; only the two contract operations are mapped.
  Responses the framework derives from these two mappings (`HEAD` for the `GET`, `OPTIONS` and CORS preflight,
  `405` for a wrong method on a known path) are HTTP protocol behaviour, not additional endpoints.
- **No additional routes:** no springdoc, no actuator, no other controllers, and Spring Boot's default
  static-resource handler is disabled.
- **Spring Boot `/error`:** not exposed as a route. Errors handled by Spring MVC are returned as Problem Details
  (ADR-0003), including `404` for unknown paths. Assumption: the framework's error fallback is not a developed
  endpoint, but it is a reachable route under reading 2, so it is removed rather than argued away.
- **Unverified framework behaviour:** how Spring Boot 4.1 derives `HEAD`/`OPTIONS`/`405`, disables the
  static-resource handler and `/error`, and renders `404`/`405` as Problem Details is not yet verified. The REST
  step chooses the mechanisms from the Spring Boot documentation and confirms them with the integration test below.
- **Swagger UI:** a separate Swagger UI container in Docker Compose loads the read-only `openapi.yaml`; the README
  explains how to trigger both operations from it (and with `curl`).
- **Health checks:** Compose uses the PostgreSQL container healthcheck; the application has no health route.

## Consequences
- The README's trigger instructions rely on the Swagger UI container, not on the application.
- **Swagger UI to API calls:** the contract's server URL is relative (`/api/v1`), so a UI served from another
  origin would call itself. The Docker step decides how the UI reaches the API (e.g. an additional absolute local
  server in the contract — a contract change, approved separately). The API then needs CORS for the UI's origin on
  both operations; the `POST` with `Idempotency-Key` additionally triggers a preflight request.
- **Errors outside Spring MVC:** without `/error`, errors raised before or outside Spring MVC's exception handling
  (e.g. requests the servlet container rejects itself) may get the container's default error response instead of
  Problem Details. This narrows the fallback assumed in ADR-0003 and deviates from the contract, which declares
  `500` as Problem Details. Accepted because the brief puts error handling out of scope; the application adds no
  custom servlet filters, which keeps such errors to requests the container rejects.
- **No dedicated health or readiness route:** Compose can still probe the application through the list operation
  or a TCP check, decided in the Docker step.
- **Verification:** the REST step adds an integration test asserting that:
  - exactly the two operations are mapped;
  - an unknown path, `GET /error` and typical framework routes (`/v3/api-docs`, `/swagger-ui.html`, `/actuator`,
    `/actuator/health`, `/index.html`) return `404` Problem Details;
  - a wrong method on a known path returns `405` Problem Details;
  - `HEAD` and `OPTIONS` on both paths are answered from the existing mappings;
  - a CORS preflight for the `POST` with `Idempotency-Key` returns the expected `Access-Control-Allow-*` headers.
- **Future extensions:** documentation or monitoring routes in the application (e.g. a health route) require a new
  ADR that supersedes this one.
