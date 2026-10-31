# ADR-0005: Endpoint scope

- **Status:** Proposed
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

## Options considered
1. **Strict: the application serves only the two operations.** No springdoc, no actuator, no reachable `/error`
   route. Swagger UI runs as a separate container (Docker Compose) on the contract. Satisfies both readings and
   still gives an interactive Swagger UI; costs CORS configuration (or a same-origin proxy) and one extra service.
2. **Operations only: documentation routes allowed.** springdoc serves Swagger UI and `/v3/api-docs` from the
   application. Convenient; under reading 2 the application serves more than two endpoints.
3. **Option 2 plus actuator `/health`.** Adds a health check for Compose; a further route under reading 2.
4. **Documentation and health routes only under a `dev` profile.** Off by default; two configurations to run and
   explain, and a reviewer may still start the `dev` profile.

## Decision
**Option 1.** The running application serves exactly the two operations of `openapi.yaml`, so the requirement holds
under both readings.

- **No additional routes:** no springdoc, no actuator, no other controllers or static content.
- **Spring Boot `/error`:** not exposed as a route. Errors are returned as Problem Details (ADR-0003) by the
  application's exception handling, including `404` for unknown paths. Assumption: the framework's error fallback
  is not a developed endpoint, but it is a reachable route under reading 2, so it is removed rather than argued
  away. The mechanism is chosen and verified against the Spring Boot documentation in the REST step.
- **Swagger UI:** a separate Swagger UI container in Docker Compose loads the read-only `openapi.yaml`; the README
  explains how to trigger both operations from it (and with `curl`).
- **Health checks:** Compose uses the PostgreSQL container healthcheck; the application has no health route.

## Consequences
- The README's trigger instructions rely on the Swagger UI container, not on the application.
- **Swagger UI to API calls:** the contract's server URL is relative (`/api/v1`), so a UI served from another
  origin would call itself. The Docker step resolves this either by adding an absolute local server to the
  contract (a contract change, approved separately) or by serving UI and API from the same origin via a proxy;
  on a different origin the API needs CORS for the UI's origin (the `Idempotency-Key` header triggers a
  preflight request).
- **No application health route:** container orchestration cannot probe the application's readiness; acceptable
  for this exercise and recorded as a future extension.
- **Verification:** the REST step adds an integration test asserting that only the two operations are mapped and
  that an unknown path returns a `404` Problem Details response.
- **Future extensions:** documentation or monitoring routes in the application require a new ADR that
  supersedes this one.
