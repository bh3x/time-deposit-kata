# ADR-0008: Hexagonal architecture

- **Status:** Proposed
- **Date:** 2026-10-31

## Context
The brief prefers "Hexagonal Architecture" and requires extensibility ("accommodate future complexities"). Until
now `CLAUDE.md` held a provisional layout. This ADR fixes the structure of the Java solution: packages, ports,
wiring, transactions, persistence technology and how the boundaries are enforced. Runtime consistency of the
balance update (locking, idempotency flow, overflow) is decided in the planned *Balance update consistency* ADR;
the interest policies in the planned *Interest calculation design* ADR.

**Decision drivers**
1. **Extensibility:** options are judged by how they hold up when capabilities are added later (e.g. a single
   deposit by id, creating withdrawals, new plan types), not by the two endpoints that exist today.
2. **Spring Boot idioms:** code must read naturally for Spring Boot developers; a deviation from a Spring idiom
   needs a concrete reason.
3. **Hard constraints:** `org.ikigaidigital.TimeDeposit` (including its package) and
   `TimeDepositCalculator.updateBalance` stay unchanged.
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
2. **Top-level layout:** layers first, capability inside each layer · **capability first, a hexagon inside each**
   (package by component; [Graça][graca]) · one hexagon without capabilities.
3. **Legacy classes:** root package counts as domain · new domain entity with the legacy classes as facade
   (duplicated model) · real classes moved to `domain` with same-name subclasses in the root (fragile, arguably
   breaking).
4. **Read model for the list:** query port returning views composed around the legacy object · independent view
   records with their own fields (bypasses ADR-0004's `Double` conversion rule) · a domain aggregate for reads and
   writes (loads withdrawals the update never needs) · `TimeDeposit` extended additively (allowed by the brief,
   but mixes API identity into the shared class).
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
├─ TimeDeposit.java                legacy, unchanged — shared domain kernel
├─ TimeDepositCalculator.java      legacy signature — shared domain kernel
├─ timedeposit/                    capability: time deposits
│  ├─ domain/                      TimeDepositTerms, ReadOnlyTimeDeposit, interest/ (interest ADR)
│  ├─ application/
│  │  ├─ port/in/                  ListTimeDepositsUseCase, ApplyMonthlyInterestUseCase
│  │  ├─ port/out/                 LoadTimeDepositViewsPort, LoadTimeDepositsPort, SaveTimeDepositsPort,
│  │  │                            BalanceUpdateRecordPort
│  │  ├─ model/                    TimeDepositView, WithdrawalView, BalanceUpdateResult
│  │  └─ service/                  ListTimeDepositsService, ApplyMonthlyInterestService
│  └─ adapter/
│     ├─ in/rest/                  TimeDepositsController (implements the generated TimeDepositsApi)
│     └─ out/persistence/          JPA entities, Spring Data repositories, persistence adapters
├─ shared/                         technical, cross-capability, no business logic
│  ├─ application/UseCase.java     stereotype annotation
│  └─ adapter/in/rest/             money formatting (ADR-0004), Problem Details handler (ADR-0003/0005)
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
3. **Legacy classes stay in the root package and form the shared domain kernel.** Nothing moves (hard
   constraint); every capability may use them.
4. **Read model: a query port returning views composed around the legacy object.** `TimeDepositView(UUID id,
   TimeDepositTerms terms, List<WithdrawalView> withdrawals)`. `TimeDepositTerms` is a read-only interface
   (`getPlanType`, `getBalance`, `getDays`) implemented by `ReadOnlyTimeDeposit`, a final wrapper without an
   accessor to the mutable `TimeDeposit` and with identity equality. One deposit model serves reads and writes;
   the REST adapter converts the `Double` balance as ADR-0004 specifies; the internal key is not exposed. Separate
   query ports for reads are the common CQRS-lite practice inside one service ([Fowler][fowler-cqrs],
   [Dahan][dahan]). Withdrawals are listed by `date`, then internal id (deterministic, not guaranteed —
   ADR-0004).
5. **Ports: `port.in` / `port.out`, named by purpose.** Driving ports end in `UseCase`, driven ports in `Port`;
   names state the purpose (`LoadTimeDepositsPort`, `SaveTimeDepositsPort`), never a table or technology.
   `package-info.java` documents `in` as the driving (primary) side and `out` as the driven (secondary) side.
   Driving-port interfaces with one implementation are kept deliberately: they are the application's documented
   API and the seam that application tests drive.
6. **Wiring: `@UseCase`, a stereotype meta-annotated with `@Service`** (as `@RestController` is with
   `@Controller`; [buckpal][buckpal-usecase]). Default component scanning applies; a new use case is one annotated
   class. `TimeDepositCalculator` cannot be annotated and is the one explicit `@Bean`.
7. **Transactions: `@Transactional` on use-case services.** Adapters call services only through `port.in`
   interfaces, so every call passes Spring's proxy and each use case has one public entry point — this avoids
   the self-invocation and non-public-method pitfalls of proxy-based transactions.
8. **Framework use:** the domain (including the legacy classes) has no framework dependencies. The application
   layer may use only `org.springframework.stereotype` and `org.springframework.transaction.annotation`. A fully
   Spring-free core would need a custom scan filter and an own transaction abstraction, which deviates from Spring
   idioms without a concrete benefit here; whether frameworks may enter the core is disputed among practitioners
   ([Rentea][rentea], [Seemann][seemann]).
9. **Main class in the root package**, so component scanning and entity/repository discovery work as Spring Boot
   documents them.
10. **Persistence: Spring Data JPA.** JPA entities exist only in `adapter.out.persistence` and never cross a port
    (two-way mapping: entity ↔ `TimeDeposit`, `Double` ↔ `NUMERIC(15,2)` per ADR-0006). Read views use explicit
    fetch-join queries. PostgreSQL specifics (e.g. locks, conflict handling for idempotency) use native queries
    inside the adapter.

**Mapping strategies** ([Hombergs][buckpal]): web one-way (view → generated DTO); persistence two-way (entity ↔
`TimeDeposit`); read view composition (no copy of legacy fields).

### Rules (enforced by ArchUnit)
1. Inside a capability: `adapter → application → domain`; `adapter.in` and `adapter.out` do not depend on each
   other.
2. Between capabilities: only through the other capability's `application.port.in`; no cycles.
3. The root package contains only `TimeDeposit`, `TimeDepositCalculator` (domain, JDK only) and
   `TimeDepositApplication`. Package patterns would also match the adapters, so the legacy classes are selected
   by class name.
4. `shared` contains no business logic and no business ports.
5. `..domain..` and the legacy classes depend on no framework; `..application..` may use only
   `org.springframework.stereotype..` and `org.springframework.transaction.annotation..`; JPA types only in
   `..adapter.out.persistence..`; generated OpenAPI types only in `..adapter.in.rest..`.
6. One OpenAPI tag per capability; each controller implements its generated interface (generator settings
   `interfaceOnly`, `skipDefaultInterface`, `useSpringBoot4` — verified in the tooling step), so an operation
   added to the contract fails compilation until it is implemented.
7. Every implementation of a `port.in` interface is a `@UseCase` in `..application.service..`; `@Transactional`
   appears only there.

## Consequences
- The provisional layout in `CLAUDE.md` is replaced by a reference to this ADR; the code-reviewer agent's boundary
  rule follows rule 5.
- ArchUnit becomes a test dependency (version pinned in the tooling step via the version catalog).
- Application tests construct services with in-memory fakes of the `port.out` interfaces (no Spring, no mocks of
  every layer); persistence adapters are tested with Testcontainers; one context test checks the wiring.
- The JPA mapping must handle the quoted camelCase identifiers of ADR-0006; the integration tests cover it.
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
