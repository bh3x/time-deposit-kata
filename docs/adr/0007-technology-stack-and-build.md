# ADR-0007: Technology stack and build

- **Status:** Proposed
- **Date:** 2026-10-31

## Context
The brief leaves the stack open ("Use any tools, frameworks, or libraries you find suitable") and prefers an
OpenAPI contract, hexagonal architecture and Testcontainers. The Java starter in `java/` is a plain Maven project
on Java 17 with JUnit 5 and AssertJ, without a wrapper or Spring. The target stack has so far only been a working
assumption in `CLAUDE.md` (Maven Wrapper, Java 25, Spring Boot 4.1); ADR-0002 mentions "the Maven build" in passing.
The tooling step needs the build tool, Java version and framework line decided first.

Facts checked on 2026-10-31 in primary sources:
- Spring Boot 4.1 (4.1.2 at the time of writing) requires Java 17 or later and supports up to Java 27; it supports
  Maven 3.6.3 or later and Gradle 8.14 or later / 9.x ([system requirements][boot-req],
  [4.1 release notes][boot-41]).
- Java 25 is an LTS release (GA 2025-09-16); Oracle's free updates for JDK 21 end in October 2026
  ([Oracle roadmap][oracle], [JDK 25][jdk25]).
- Gradle runs on and compiles for Java 25 from 9.1.0 ([compatibility matrix][gradle-compat],
  [9.1.0 release notes][gradle-91]). Maven 4.0 is still a release candidate; the stable line is 3.9.x
  ([4.0.0-rc-7 notes][maven-rc7]).

## Options considered
**Build tool**
1. **Maven 3.9.x with Wrapper** — keeps the starter's tool; declarative and predictable; failsafe `*IT` convention;
   no build cache or real incremental build; verbose for multiple modules.
2. **Gradle 9.x, Kotlin DSL, with Wrapper** — actively developed; type-safe build script with IDE completion;
   version catalogs; `jvm-test-suite` for a separate integration-test source set; incremental build and build
   cache; Spring Boot itself is built with Gradle. The build script is code, so it can be over-engineered, and it
   replaces the starter's `pom.xml`.

**Java version:** 17 (starter; oldest supported LTS) · 21 LTS (widely deployed; free Oracle updates end October
2026) · 25 LTS (current LTS, longest support).

**Framework line:** Spring Boot 4.1.x (current; Spring Framework 7, Jakarta EE 11) · Spring Boot 3.5.x (previous
generation; an older baseline for a new project).

**Integration tests:** separate `integrationTest` suite (`jvm-test-suite`) · `*IT` naming inside `src/test` with a
filtered extra test task.

**Dependency versions:** Spring Boot dependency management plus a version catalog · Spring Boot dependency
management plus versions written inline in the build script.

## Decision
- **Build tool: Gradle 9.x with the Kotlin DSL and the Gradle Wrapper** (at least 9.1.0 for Java 25; the exact
  version is pinned from the official release page in the tooling step). On merit it is the more modern tool and
  fits the stack at least as well as Maven; the project is small, so its value is the type-safe script and the
  separate test suites rather than build speed. Guard rail: no custom build logic beyond plugin configuration, so
  the build stays as readable as a declarative one.
- **Java 25 LTS**, configured as a Gradle toolchain so the build uses Java 25 regardless of the JVM that starts
  Gradle. The legacy code compiles unchanged.
- **Spring Boot 4.1.x** (exact patch pinned in the tooling step).
- **Integration tests: a separate `integrationTest` suite** (`src/integrationTest/java`, own task). `./gradlew test`
  runs unit tests only and needs no Docker; `./gradlew check` runs unit and integration tests (Testcontainers,
  Docker required). This replaces the `*IT` naming convention.
- **Dependency versions:** Spring Boot's dependency management supplies the versions it manages; every other
  version (plugins, openapi-generator, ArchUnit and similar) lives in `gradle/libs.versions.toml`, one place with
  type-safe accessors. How Boot's BOM is applied (Gradle platform or the dependency-management plugin) is chosen
  in the tooling step.
- **Location:** the Gradle build is rooted in `java/` (`settings.gradle.kts`, `build.gradle.kts`, `gradlew`,
  `gradle/`); the other language folders stay untouched. The Kotlin DSL is used only for the build script —
  production and test code stay Java.

## Consequences
- The tooling step replaces the starter's `pom.xml` with the Gradle build, adds the wrapper and moves the build to
  Java 25; the existing calculator test must stay green.
- Commands change: `./gradlew test` (unit), `./gradlew check` (all tests), `./gradlew bootRun` (run locally).
  `CLAUDE.md` is updated in this PR; `.claude/settings.json` (allows `./mvnw`) and `AI_USAGE.md` are updated in the
  tooling step together with the build.
- ADR-0002's remark about "the Maven build" is read as "the build": the generator plugin is configured in Gradle.
- Compiler warnings as errors (`-Xlint:all -Werror`, CLAUDE.md rule 35) are configured on the Java compile tasks,
  with generated sources excluded or suppressions justified there.
- Versions are pinned only from primary sources (CLAUDE.md rule 20).

[boot-req]: https://docs.spring.io/spring-boot/system-requirements.html
[boot-41]: https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.1-Release-Notes
[oracle]: https://www.oracle.com/java/technologies/java-se-support-roadmap.html
[jdk25]: https://openjdk.org/projects/jdk/25/
[gradle-compat]: https://docs.gradle.org/current/userguide/compatibility.html
[gradle-91]: https://docs.gradle.org/9.1.0/release-notes.html
[maven-rc7]: https://maven.apache.org/docs/4.0.0-rc-7/release-notes.html
