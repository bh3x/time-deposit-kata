# Time Deposit Refactoring Kata - Take-Home Assignment

## Solution: run and try the API
The solution is the Java implementation in [`java/`](java/). Design decisions are recorded in [`docs/adr/`](docs/adr/).

### Start the stack
Prerequisite: Docker (with Compose). No JDK is needed.

```bash
cd java
docker compose up --build
```

The first build downloads Gradle and the dependencies inside the image and takes a few minutes. The stack is ready
once the log shows `Started TimeDepositApplication`.

| Service | URL | Notes |
|---|---|---|
| Swagger UI | http://localhost:8081 | Interactive client for the OpenAPI contract |
| API | http://localhost:8080/api/v1 | The two operations of the contract |
| PostgreSQL | inside the stack only | Schema created by Flyway; demo data loaded on startup |

Both ports are published on `localhost` only.

### Verify the stack (smoke check)
- `docker compose ps` lists `postgres` as healthy and `app` and `swagger-ui` as running.
- `curl http://localhost:8080/api/v1/time-deposits` returns a JSON array of 15 demo deposits.
- http://localhost:8081 shows the "XA Bank Time Deposit API" contract.
- `curl -i http://localhost:8080/actuator/health` returns `404`: the application serves only the two operations.

### Trigger the endpoints with Swagger UI
1. Open http://localhost:8081 (use `localhost`, not `127.0.0.1`: the API allows cross-origin calls from
   `http://localhost:8081` only). The contract (`openapi.yaml`) is loaded; keep the server
   **`http://localhost:8080/api/v1` (Local Docker Compose stack)** selected.
2. **List all time deposits:** expand `GET /time-deposits` → **Try it out** → **Execute**. The response is a JSON
   array of deposits with `id`, `planType`, `balance`, `days` and `withdrawals`.
3. **Apply monthly interest:** expand `POST /balance-updates` → **Try it out** → optionally enter an
   `Idempotency-Key` (repeating a key returns the first outcome instead of applying interest again) → **Execute**.
   Then run step 2 again to see the new balances.

> **Current state:** the balance update is implemented in a later step. Until then `POST /balance-updates`
> answers `500` with a Problem Details body.

### Trigger the endpoints with curl
```bash
curl http://localhost:8080/api/v1/time-deposits
curl -X POST -H "Idempotency-Key: $(uuidgen)" http://localhost:8080/api/v1/balance-updates
```
A new key per call applies interest again; reusing a key returns the first outcome. The header is optional.

### Stop and reset
- `docker compose down` stops the stack; the database volume and its balances are kept.
- `docker compose down -v` also deletes the database; the next start loads the demo data again.

### Notes
- The application serves only the two API operations; Swagger UI runs as a separate container (ADR-0005), which
  the API allows as a cross-origin caller (`app.cors.allowed-origins`, ADR-0010).
- Run the application without Docker against your own PostgreSQL: `cd java && ./gradlew bootRun` with
  `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME` and `SPRING_DATASOURCE_PASSWORD` set, plus
  `SPRING_PROFILES_ACTIVE=demo` for the demo data (without it the list starts empty). Gradle needs a JDK 17 or
  newer to start; it downloads the JDK 25 toolchain itself if none is installed.
- Tests: `cd java && ./gradlew check` runs the unit, architecture and integration tests (Docker required for
  Testcontainers). See [`docs/testing.md`](docs/testing.md).

---

## XA Bank Time Deposit

### Context
A junior developer implemented domain logic for a time deposit system but did not complete the API functionality. Your task is to refactor the existing codebase to implement all required functionalities based on the provided business requirements, ensuring no breaking changes occur.

### Requirements

1. **API Endpoints**:
    - Create a RESTful API endpoint to update the balances of all time deposits in the database.
    - Create a RESTful API endpoint to retrieve all time deposits.
        - The GET endpoint should return a list of all time deposits with the following schema:
            - `id`
            - `planType`
            - `balance`
            - `days`
            - `withdrawals`

2. **Database Setup**:
    - Store all time deposit plans in a database.
    - Define the following tables:
        - `timeDeposits`:
            - `id`: Integer (primary key)
            - `planType`: String (required)
            - `days`: Integer (required)
            - `balance`: Decimal (required)
        - `withdrawals`:
            - `id`: Integer (primary key)
            - `timeDepositId`: Integer (foreign key, required)
            - `amount`: Decimal (required)
            - `date`: Date (required)

3. **Interest Calculation**:
    - Implement logic to calculate monthly interest based on the plan type:
        - **Basic Plan**: 1% interest
        - **Student Plan**: 3% interest (no interest after 1 year)
        - **Premium Plan**: 5% interest (interest starts after 45 days)
    - No interest is applied for the first 30 days for any existing plans.

4. **Refactoring Constraints**:
    - Do not introduce breaking changes to the shared `TimeDeposit` class or modify the `updateBalance` method signature.
    - Ensure the design is extensible to accommodate future complexities in interest calculations.

5. **Code Quality**:
    - Adhere to SOLID principles, design patterns, and clean code practices where applicable.

6. **AI-Assisted Development**:
    - Set up an AI harness or agent workflow and use it throughout the development for this take-home exercise.
    - Briefly document the tools and setup used (e.g., LLMs, coding assistants, agentic frameworks, configuration).
    - Ensure your AI setup is practical and reproducible.
    - Include any custom rules, system prompts, or agent configurations used.
    - Include a brief summary of which parts of the solution were AI-assisted and why.

### Important Guidelines
- The existing `TimeDepositCalculator.updateBalance` method is functioning correctly. Ensure its behavior remains unchanged after refactoring.
- The final solution must include **exactly two API endpoints**. Do not develop additional endpoints.
- **Do not** create a pull request or a new branch in the ikigai-digital repository. Instead, fork the repository into your own GitHub repository and develop the solution there.
- Handling invalid input or exceptions is not required.
- Use any tools, frameworks, or libraries you find suitable.
- In case of ambiguity, make logical assumptions and justify them in code comments.

### Preferred Stack
- Use an OpenAPI Swagger contract.
- Embrace Hexagonal Architecture.
- Follow atomic commit practices.
- Utilize testcontainers.
- Leverage AI-assisted development tools for code generation, testing, and refactoring.

### Submission Instructions
- Provide clear instructions on how to trigger the endpoints using the Swagger contract.
- Email the link to your public GitHub repository.