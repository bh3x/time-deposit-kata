# ADR-0008: Hexagonal architecture

- **Status:** Proposed
- **Date:** 2026-10-31

## Context
The brief prefers "Hexagonal Architecture" and requires extensibility ("accommodate future complexities"). Until
now `CLAUDE.md` held a provisional layout. This ADR fixes the structure of the Java solution: packages, ports,
wiring, transactions, persistence technology and how the boundaries are enforced. The interest policies are
decided in the planned *Interest calculation design* ADR.

**Handed over to the planned *Balance update consistency* ADR.** ADR-0004 and ADR-0006 deferred these items to
"the planned *Hexagonal architecture* ADR"; they are runtime consistency, not structure, and move on:
- serialising concurrent balance update runs (ADR-0004), and protecting future writers of balances;
- in-flight detection for `409`, the in-flight timeout and the takeover of stale `IN_PROGRESS` rows
  (ADR-0004, ADR-0006);
- the order of idempotency key check and lock (ADR-0006);
- how the idempotency reservation is committed before the balance transaction, and whether this refines rules 5
  and 7 below (ADR-0006);
- retention cleanup after 24 hours (ADR-0004, ADR-0006);
- handling of a single deposit that fails, e.g. exceeds `NUMERIC(15,2)` (ADR-0006);
- behaviour as the number of deposits grows.

**Decision drivers**
1. **Extensibility:** options are judged by how they hold up when capabilities are added later (e.g. a single
   deposit by id, creating withdrawals, new plan types), not by the two endpoints that exist today.
2. **Spring Boot idioms:** code must read naturally for Spring Boot developers; a deviation from a Spring idiom
   needs a concrete reason.
3. **Hard constraints:** the brief protects the **shared `TimeDeposit` class** (no breaking changes, so its
   package stays) and the **signature and behaviour of `updateBalance`**. The package of `TimeDepositCalculator` is
   not protected: a Java method signature is its name and parameter types. Callers of the calculator inside this
   codebase are updated when it moves.
4. **Testability:** the application runs and is tested without UI and database — the original purpose of the
   pattern ([Cockburn][cockburn]).

**Terms used** (from the pattern's sources): Cockburn's Ports & Adapters has exactly two regions, inside (the
application) and outside (adapters). A *port* is a purposeful conversation, expressed as interfaces owned by the
application. *Driving* (primary) ports are the API the application provides; *driven* (secondary) ports are what
it requires; adapters live outside and convert between a technology and a port ([Cockburn][cockburn],
[Garrido de Paz][jmg]). The pattern deliberately says nothing about the inside; the domain/application split below
is taken from DDD and Onion architecture ([Graça][graca], [Palermo][palermo]). The `port.in`/`port.out` naming
follows Hombergs' widely used Spring layout ([buckpal][buckpal]).

## Options considered
1. **Enforcing boundaries:** one Gradle module with ArchUnit tests · Gradle multi-project (compiler-enforced, needs
   convention plugins) · Java modules (JPMS; friction with Spring and generated code) · Spring Modulith.
2. **Top-level layout:** layers first, capability inside each layer (a capability is spread over several trees) ·
   **capability first, a hexagon inside each** (package by component; [Graça][graca]) · one hexagon without
   capabilities.
3. **Legacy classes:** `TimeDeposit` stays in the root package (forced). For `TimeDepositCalculator`: stay in the
   root (the shared kernel then depends on the interest policies) · move into the capability's domain next to its
   policies · move and keep a deprecated delegating class in the root (one more class, protects external callers
   under a strict reading). For the domain model: a new entity with the legacy classes as facade was rejected
   (duplicated model).
4. **Read model for the list:** query port returning views composed around the legacy object · independent view
   records with their own fields (a second model duplicating the legacy fields, to keep in step) · a domain
   aggregate for reads and writes (loads withdrawals the update never needs) · `TimeDeposit` extended additively
   (allowed by the brief, but mixes API identity into the shared class).
5. **Port naming:** `in`/`out` with use-case/port suffixes (Hombergs) · `driving`/`driven` · Cockburn's
   "For…ing" names.
6. **Wiring:** stereotype scanning (`@Service`) · own stereotype meta-annotated with `@Service` · Spring-free own
   annotation with a custom scan filter · explicit `@Bean` methods.
7. **Transactions:** `@Transactional` on use-case services · own annotation plus aspect · `UnitOfWork` port with
   `TransactionTemplate` · `jakarta.transaction.Transactional`.
8. **Framework use inside the core:** none at all · domain framework-free, application allowed stereotype and
   transaction annotations only.
9. **Main class:** root package (Spring Boot default) · `configuration` package (needs explicit scan settings).
10. **Persistence:** Spring Data JPA · JPA for writes plus `JdbcClient` for reads · `JdbcClient` only · Spring Data
    JDBC (aggregate model would put withdrawals inside the deposit aggregate) · jOOQ (code generation at build).

## Decision

### Layout
```
org/ikigaidigital/
├─ TimeDepositApplication.java     @SpringBootApplication (root package: Spring Boot defaults apply)
├─ TimeDeposit.java                legacy, unchanged — the shared domain kernel
├─ timedeposit/                    capability: time deposits
│  ├─ domain/                      TimeDepositCalculator (moved, signature and behaviour unchanged),
│  │                               TimeDepositTerms, ReadOnlyTimeDeposit, interest/ (interest ADR)
│  ├─ application/
│  │  ├─ port/in/                  ListTimeDepositsUseCase, ApplyMonthlyInterestUseCase
│  │  ├─ port/out/                 LoadTimeDepositViewsPort, LoadTimeDepositsPort, SaveTimeDepositsPort,
│  │  │                            RecordBalanceUpdatePort
│  │  ├─ model/                    TimeDepositView, WithdrawalView, BalanceUpdateResult
│  │  └─ service/                  ListTimeDepositsService, ApplyMonthlyInterestService
│  └─ adapter/
│     ├─ in/rest/                  TimeDepositsController (implements the generated TimeDepositsApi)
│     └─ out/persistence/          JPA entities, Spring Data repositories, persistence adapters
├─ shared/                         technical, cross-capability, no business logic
│  ├─ application/UseCase.java     stereotype annotation
│  └─ adapter/in/rest/             money formatting (ADR-0004), Problem Details handler (ADR-0003/0005),
│                                  api/ and model/: generated OpenAPI interfaces and DTOs (all tags)
└─ configuration/                  @Configuration classes (e.g. the TimeDepositCalculator bean)
```
Tests mirror these packages in `src/test` (unit, architecture) and `src/integrationTest` (ADR-0007). A future
capability (e.g. `withdrawal/`) gets the same inner layout.

### Decisions per topic
1. **One Gradle module, boundaries enforced by ArchUnit.** Checked at test time with readable failures; a
   multi-project build or Spring Modulith can follow later without moving code, because every capability package
   maps one-to-one to a future module.
2. **Capability first, hexagon inside.** A capability is a business area with its own use cases and the data it
   writes (time deposits; later e.g. withdrawals) — not a package per endpoint or per table. Adding a capability
   adds one package instead of touching every layer. Each table is written by exactly one capability; read
   models may join across tables in the same database.
3. **`TimeDeposit` stays in the root package as the shared domain kernel; `TimeDepositCalculator` moves to
   `timedeposit/domain`,** next to the interest policies it will delegate to. The kernel then depends only on the
   JDK and on no capability. The move is a refactoring of the calculator, so it happens after the characterization
   tests are in place (`CLAUDE.md`); signature and behaviour of `updateBalance` stay identical.
4. **Read model: a query port returning views composed around the legacy object.** `TimeDepositView(UUID id,
   TimeDepositTerms terms, List<WithdrawalView> withdrawals)`. `TimeDepositTerms` is a read-only interface
   (`getPlanType`, `getBalance`, `getDays`) implemented by `ReadOnlyTimeDeposit`, a final wrapper without an
   accessor to the mutable `TimeDeposit` and with identity equality. One deposit model serves reads and writes,
   without a second set of the legacy fields; the REST adapter converts the `Double` balance as ADR-0004 specifies;
   the internal key is not exposed. Separate query ports for reads are the common CQRS-lite practice inside one
   service ([Fowler][fowler-cqrs], [Dahan][dahan]). **Order:** time deposits by internal key (ADR-0004);
   withdrawals within a deposit by `date`, then internal id — decided here, deterministic but not guaranteed by
   the contract.
5. **Ports: `port.in` / `port.out`, named by purpose.** Driving ports end in `UseCase`, driven ports in `Port`;
   names start with the purpose (`LoadTimeDepositsPort`, `RecordBalanceUpdatePort`), never a table or technology.
   `package-info.java` documents `in` as the driving (primary) side and `out` as the driven (secondary) side.
   Driving-port interfaces with one implementation are kept deliberately: they are the application's documented
   API and the seam that application tests drive.
6. **Wiring: `@UseCase`, a stereotype meta-annotated with `@Service`** (as `@RestController` is with
   `@Controller`), adapted from [buckpal][buckpal-usecase] but without `@AliasFor`, so it uses only
   `org.springframework.stereotype`. Default component scanning applies; a new use case is one annotated class.
   `TimeDepositCalculator` is the one explicit `@Bean`, so the domain stays free of annotations.
7. **Transactions: `@Transactional` on use-case services.** Adapters call services only through `port.in`
   interfaces, so every call passes Spring's proxy and each use case has one public entry point — this avoids
   the self-invocation and non-public-method pitfalls of proxy-based transactions. Whether and how a separate
   transaction is opened (ADR-0006's reservation) is left to the *Balance update consistency* ADR, which may
   refine rules 5 and 7.
8. **Framework use:** the domain (including `TimeDeposit`) has no framework dependencies. The application layer
   may use only `org.springframework.stereotype` and `org.springframework.transaction.annotation`. A fully
   Spring-free core would need a custom scan filter and an own transaction abstraction, which deviates from Spring
   idioms without a concrete benefit here; whether frameworks may enter the core is disputed among practitioners
   ([Rentea][rentea], [Seemann][seemann]).
9. **Main class in the root package**, so component scanning and entity/repository discovery work as Spring Boot
   documents them.
10. **Persistence: Spring Data JPA** — the persistence stack Spring Boot developers expect, and the one that scales
    best to more entities and relations as capabilities are added; `JdbcClient` would mean hand-written queries
    and row mapping for every new use case. JPA entities exist only in `adapter.out.persistence` and never cross a
    port. Read views use explicit fetch-join queries. PostgreSQL specifics (e.g. locks, conflict handling for
    idempotency) use native queries inside the adapter.

**Mapping strategies** ([Hombergs][buckpal]):
- web: one-way (view → generated DTO);
- persistence: two-way (entity ↔ `TimeDeposit`, `Double` ↔ `NUMERIC(15,2)` per ADR-0006). `TimeDeposit` has no
  `publicId`, so saving never builds new entities from it: it updates the balance of the entities loaded in the
  same transaction (or updates by internal id), and `publicId` is not updatable;
- read view: composition (no copy of legacy fields).

### Rules (enforced by ArchUnit, on production classes only)
1. **Allowed dependencies:**

   | From | May depend on |
   |---|---|
   | `<capability>.adapter..` | its own `application` and `domain`, the kernel (`TimeDeposit`), `shared` |
   | `<capability>.application..` | its own `domain`, the kernel, `shared.application` |
   | `<capability>.domain..` | the kernel |
   | `shared..` | the kernel (no capability) |
   | `configuration..`, `TimeDepositApplication` | anything |

   `adapter.in` and `adapter.out` do not depend on each other.
2. **Between capabilities:** only through the other capability's `application.port.in`; no cycles.
3. **Root package:** contains only `TimeDeposit` (domain, JDK only) and `TimeDepositApplication`. The rules select
   both by class name, which states the intent explicitly.
4. `shared` contains no business logic and no business ports.
5. **Frameworks:** `..domain..` and `TimeDeposit` depend on no framework; `..application..` may use only
   `org.springframework.stereotype..` and `org.springframework.transaction.annotation..`; JPA types only in
   `..adapter.out.persistence..`; generated OpenAPI types (`shared.adapter.in.rest.api/.model`) only in
   `..adapter.in.rest..`.
6. **Contract:** one OpenAPI tag per capability; each controller implements its generated interface (generator
   settings `interfaceOnly`, `skipDefaultInterface`, `useSpringBoot4` — verified in the tooling step), so an
   operation added to the contract fails compilation until it is implemented.
7. **Use cases:** every implementation of a `port.in` interface is a `@UseCase` in `..application.service..`;
   `@Transactional` appears only there.

## Consequences
- The provisional layout in `CLAUDE.md` is replaced by a reference to this ADR; the code-reviewer agent's boundary
  rule follows rule 5.
- ArchUnit becomes a test dependency (version pinned in the tooling step via the version catalog).
- Application tests construct services with in-memory fakes of the `port.out` interfaces (no Spring, no mocks of
  every layer); persistence adapters are tested with Testcontainers; one context test checks the wiring.
- The calculator move and the existing calculator test's package change happen in the refactoring step, after
  the characterization tests.
- The JPA mapping must handle the quoted camelCase identifiers of ADR-0006; the integration tests cover it,
  including that saving balances leaves `publicId` and `days` unchanged.
- Withdrawals are read by the `timedeposit` capability today; when a `withdrawal` capability is added, it takes
  over writing that table and the read model keeps joining it.
- Adding a capability: new package with the same inner layout, a new OpenAPI tag, ArchUnit rules apply
  unchanged.
- Introducing Gradle modules or Spring Modulith later is a new ADR; the package structure already matches.

[cockburn]: https://alistair.cockburn.us/hexagonal-architecture/
[jmg]: https://jmgarridopaz.github.io/content/hexagonalarchitecture.html
[graca]: https://herbertograca.com/2017/11/16/explicit-architecture-01-ddd-hexagonal-onion-clean-cqrs-how-i-put-it-all-together/
[palermo]: https://jeffreypalermo.com/2008/07/the-onion-architecture-part-1/
[buckpal]: https://github.com/thombergs/buckpal
[buckpal-usecase]: https://github.com/thombergs/buckpal/blob/main/src/main/java/io/reflectoring/buckpal/common/UseCase.java
[fowler-cqrs]: https://martinfowler.com/bliki/CQRS.html
[dahan]: https://udidahan.com/2009/12/09/clarified-cqrs/
[rentea]: https://victorrentea.ro/blog/overengineering-in-onion-hexagonal-architectures/
[seemann]: https://blog.ploeh.dk/2013/12/03/layers-onions-ports-adapters-its-all-the-same/
