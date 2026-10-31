# ADR-0003: API conventions

- **Status:** Proposed
- **Date:** 2026-10-31

## Context
Before designing resources, the API needs common rules for representations, errors and naming that work with
the target stack (Spring Boot 4.1 / Jackson, openapi-generator `spring`, OpenAPI 3.0.3) and with the brief
(camelCase field names, "return a list").

## Options considered
**Envelope:** none · simple `data/meta` wrapper · HAL (Spring HATEOAS) · JSON:API.
**Errors:** RFC 9457 Problem Details · not specified · custom error envelope.
**Style guide:** Zalando · Google AIP · Microsoft REST Guidelines · own conventions.
Each published guide conflicts with the brief or with the decisions below (Zalando: snake_case, top-level
objects only, no URI versioning; Google AIP and Microsoft: wrapped collections and their own error formats).

## Decision
- **No envelope:** resources and collections are returned as plain JSON (a collection is a JSON array).
- **Errors:** RFC 9457 Problem Details (`application/problem+json`), one shared schema.
- **Representation:** `application/json`; camelCase field names; dates as ISO-8601 / RFC 3339 (`YYYY-MM-DD`);
  date-times as RFC 3339 in UTC with `Z` (e.g. `2026-10-31T10:15:00Z`); lists are empty, never `null`;
  absent optional fields are omitted.
- **Style guide:** own conventions, inspired by the Zalando RESTful API Guidelines, adopting:
  - plural names for collection resources;
  - `x-extensible-enum` for open value sets (e.g. `planType`), so new values do not break clients.
- **Validation:** Redocly CLI recommended ruleset.

## Consequences
- Mostly Spring Boot / Jackson defaults and no extra libraries. Implementation follow-ups:
  - omit absent fields: `spring.jackson.default-property-inclusion=non_null`;
  - Problem Details responses: `spring.mvc.problemdetails.enabled=true` (covers Spring MVC's own exceptions)
    **and** a `@RestControllerAdvice` mapping any other exception to a `500` `ProblemDetail`; otherwise
    unexpected errors fall back to Spring Boot's default error JSON;
  - date-times serialised from `Instant` (or `OffsetDateTime` at UTC);
  - money as decimal strings needs an explicit mapping (see ADR-0004).
- No room for collection metadata (e.g. paging) without a breaking change; acceptable, as none is required.
- Error handling remains out of scope (brief), but the contract documents the error format.
- `x-extensible-enum` is ignored by code generators, so open value sets are plain strings in code.
