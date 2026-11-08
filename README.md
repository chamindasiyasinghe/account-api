# Account API

Spring Boot 4.1.2 / Java 17 assessment using PostgreSQL, Redis, JWT, API keys, database-backed idempotency and Swagger UI.

## Run on Windows PowerShell

Requires Java 17 JDK, Docker Compose or existing PostgreSQL/Redis, and internet access for the first Gradle build.

```powershell
docker compose up -d
.\gradlew.bat clean build
.\gradlew.bat bootRun
```

Wait for PostgreSQL to accept connections before bootRun. If PostgreSQL already occupies port 5432, use the existing instance and create the `assessment` database instead of starting another instance on that port. Set DB_URL/DB_USERNAME/DB_PASSWORD to match. Do not change the configured password of a previously initialized Docker database by only changing an environment variable; PostgreSQL initialization variables apply to a fresh volume.

In a second PowerShell terminal with the SAME JWT_SECRET/API_KEY environment values:


Open http://localhost:8090/swagger-ui.html. Choose **Authorize**, paste the JWT into bearerAuth (without the `Bearer ` prefix) and API_KEY into apiKeyAuth. Both are required. Swagger is served by the application; it does not automatically launch a browser. OpenAPI JSON is at /v3/api-docs.


## Endpoints and headers

| Endpoint | Headers | Required JWT scope |
|---|---|---|
| POST /api/accounts | Authorization: Bearer TOKEN; api_key: API_KEY; Idempotency-Key: unique-key | accounts:write |
| GET /api/accounts/{id} | Authorization: Bearer TOKEN; api_key: API_KEY | accounts:read |

POST body:

```json
{
  "customerName": "Chaminda Siyasinghe",
  "accountNickName": "Holiday savings"
}
```

Returns 201, Location: /api/accounts/{generated-id}, and id, accountNumber, customerName and accountNickName. Both ID and account number are independently generated UUIDs; the account number is an assessment identifier, not a NZ bank-routing format. GET returns the same representation. Only the JWT subject that created an account may read it. GET cache entries include account ID AND JWT subject, and successful reads expire after ten minutes.

## Request and response packages

`controller/account_request/CreateAccountRequest.java` holds the validated input DTO.
`controller/account_response/AccountResponse.java` holds the output DTO.
The old `controller/dto` package has been removed and all imports updated.

## Validation and business rules

- Customer name is mandatory, nonblank and at most 200 characters. Leading/trailing whitespace is removed and repeated whitespace collapsed.
- Nickname is optional (omit or null). When provided, its trimmed length must be 5–30 characters; blank values are invalid.
- Offensive nickname words/phrases are rejected with 422 using `src/main/resources/offensive-nicknames.txt`. The check uses Unicode NFKC normalization, case folding, and punctuation/whitespace normalization, then matches whole words/phrases. Null nicknames skip this check. The included list is a starter assessment policy, not a complete multilingual moderation system; the source contains a TODO for a bank-approved policy and obfuscation rules. Override `account.offensive-nicknames-location` with a Spring resource location such as `file:/path/to/policy.txt` to supply an external list. New creations use the current policy; replaying an already committed idempotent request returns its original result without reapplying a changed policy.
- At most five accounts per normalized customer name (case insensitive). This assessment still identifies customers by name because the requested model has no customer ID. Different people with the same name share this limit; names are not a secure customer identity. JWT subject separately controls ownership. A real banking design should introduce a customer ID tied to the authenticated identity.
- POST requires a nonblank Idempotency-Key, maximum 128 characters.

## Idempotency and database constraints

Generate ONE key for each intended account creation. Reuse that key with the same body for retries, a timed-out response, or repeated clicks on the same operation. A new key means a new intended operation and may create another account. The server cannot infer that different keys represent the same intent.

The key is scoped to the JWT subject and the POST account operation. Canonicalized customer name and nickname are hashed using SHA-256. Same caller/key/body returns the original account, status 201 and the same Location, even when the customer now has five accounts. Different body with the same caller/key returns 422. Different callers can reuse the same key without sharing a response. Invalid requests/business failures do not consume the key because their transaction rolls back.

PostgreSQL transaction-scoped advisory locks serialize each caller/key, then each normalized customer. Account creation and the idempotency row commit or roll back together at READ_COMMITTED isolation. The `uk_idempotency_owner_key` database UNIQUE constraint is the final duplicate protection. Account ID and account number are also unique. The five-account limit requires all writers to follow the service locking protocol; direct SQL inserts bypass that rule. No automatic retry loop hides database errors.

Keys are retained indefinitely in this assessment. Do not delete records while clients may still retry; deletion allows the same key to create a new operation. A production retention policy needs an agreed retry window and cleanup process.

## HTTP error responses

All errors use application/problem+json through AccountExceptionHandler (@RestControllerAdvice). Security entry points, access-denied handlers and ApiKeyFilter delegate to Spring MVC's HandlerExceptionResolver, because filter exceptions occur before controller invocation.

| Status | Meaning |
|---|---|
| 400 | Invalid body, account UUID, missing/invalid Idempotency-Key |
| 401 | Missing/invalid API key, missing/malformed/expired/invalid-signature JWT, invalid issuer/audience/required claims |
| 403 | JWT lacks required scope, or caller does not own the account |
| 404 | Account does not exist |
| 422 | Five-account cap, idempotency payload mismatch, database uniqueness/integrity constraint rejection |
| 500 | Unexpected code errors, including NullPointerException/IllegalStateException |
| 503 | Database/cache connection failures, transaction startup failure, query timeout, upstream REST 5xx/network failure, explicit ServiceUnavailableException |

500/503 bodies do not expose stack traces, SQL, credentials or upstream response bodies; details are logged server-side. An application that cannot start, a crashed JVM, or a proxy-generated error cannot be handled by controller advice. JWT and API key values are validated in filters, not trusted because they are present in Swagger.

## Database migration

All account details (ID, generated number, customer name, nickname and owner) are in ONE table, `savings_accounts`. `account_idempotency` is a separate technical retry-metadata table referencing the account ID; it does not duplicate account details. This interprets "account details in a single table" as one account-data table, while retaining the previously requested durable idempotency feature.

Flyway V1 creates accounts. V2 adds account ownership and the idempotency table. Existing V1 rows are marked `legacy-unclaimed` and are not accessible to normal JWT subjects; migrate their owner_subject explicitly using trusted identity data. Do not edit migrations already applied to a database.

## Configuration

- DB_URL: default jdbc:postgresql://localhost:5432/assessment
- DB_USERNAME: default postgres
- DB_PASSWORD: required for normal startup
- API_KEY: required, nonblank
- JWT_SECRET: required, Base64 encoding at least 32 random bytes
- JWT_ISSUER: default assessment
- JWT_AUDIENCE: default assessment-api
- REDIS_HOST: default localhost
- REDIS_PORT: default 6379

Redis is required for normal cached reads. Redis outages return 503, rather than silently bypassing the cache. Spring's default JDK cache serialization is used; response DTO implements Serializable. Use trusted Redis data. The API has no update/delete endpoints; external database edits can remain cached until expiration.

## Tests

```powershell
.\gradlew.bat clean build
```

Default tests require no external services or secrets. `AccountControllerTest` full-context MockMvc tests use H2, in-memory caching, a mocked PostgreSQL lock, REAL HS256 signatures and the real security filter chain. Tests cover replay, mismatched payloads, caller scoping, database uniqueness, validation boundaries, fifth/sixth account behavior, ownership/cache isolation, 401/403, JWT expiration/signature/issuer/audience and Swagger. `AccountControllerAdviceTest` is a MockMvc slice that injects service failures to verify 422/500/503 advice. Mockito uses the subclass mock maker, avoiding runtime agent attachment.

PostgresConcurrencyTests is opt-in and uses a REAL PostgreSQL database, Flyway migrations, real advisory locks and simultaneous MockMvc calls. It is skipped unless POSTGRES_TEST_URL is set. Use a DEDICATED DISPOSABLE test database: these tests delete all account and idempotency rows in that database.

```powershell
$env:POSTGRES_TEST_URL = "jdbc:postgresql://localhost:5432/assessment_test"
$env:POSTGRES_TEST_USERNAME = "postgres"
$env:POSTGRES_TEST_PASSWORD = "your-test-database-password"
.\gradlew.bat test --tests "*PostgresConcurrencyTests"
```

Create assessment_test beforehand. The live tests check concurrent same-key replay, concurrent different keys enforcing the cap, and concurrent conflicting payloads. These tests and real Redis integration require external services and are not covered by the default H2 tests.

See FR_COVERAGE.md for the requirement-by-requirement implementation and test mapping.

## Environment profiles and port

The default profile is `local`; the default HTTP port is **8090** in every profile. Override the port with SERVER_PORT if needed. Profiles are selected at startup, not by editing source:

```powershell
.\gradlew.bat bootRun --args="--spring.profiles.active=local"
.\gradlew.bat bootRun --args="--spring.profiles.active=uat"
.\gradlew.bat bootRun --args="--spring.profiles.active=prod"
```

Alternatively set `$env:SPRING_PROFILES_ACTIVE = "uat"` before starting the packaged JAR. Automated test classes explicitly activate `test`. The test profile uses an H2 test dependency, so it is intended for Gradle tests, not for running the production JAR (H2 is not packaged).

| Profile | Database/cache | Swagger | Required environment settings |
|---|---|---|---|
| local (default) | Local PostgreSQL and Redis defaults | Enabled | DB_PASSWORD, API_KEY, JWT_SECRET |
| test | H2 and in-memory cache; Flyway disabled | Enabled for endpoint tests | Test-only values supplied in profile |
| uat | External PostgreSQL and Redis; Redis TLS defaults to true | Enabled | DB_URL, DB_USERNAME, DB_PASSWORD, REDIS_HOST, REDIS_PASSWORD, API_KEY, JWT_SECRET, JWT_ISSUER, JWT_AUDIENCE |
| prod | External PostgreSQL and Redis; Redis TLS defaults to true | Disabled | Same as UAT |

UAT/prod database URLs should include the TLS/SSL options required by your database provider. Set REDIS_PORT and REDIS_SSL_ENABLED to match the target Redis service. No production credentials are included. The test profile's public test credentials must never be used for deployment. Select only one environment profile at a time.

ProfileConfigurationTest verifies the profile configuration loads and resolves; it does not connect to UAT/prod services. Opt-in PostgreSQL concurrency tests activate test but explicitly re-enable Flyway and schema validation for the real test database.

## Coverage reports

```powershell
.\gradlew.bat clean test jacocoTestReport
```

Open `build/reports/jacoco/test/html/index.html`. XML is at `build/reports/jacoco/test/jacocoTestReport.xml`. The report measures the combined unit and MockMvc tests; it is not a claim of 100% coverage. Optional live PostgreSQL tests remain excluded from executed coverage unless you supply the test database environment variables. See VERIFICATION.txt for the measured line/branch coverage in the latest run.

GET error responses are declared directly on AccountApi.getAccount: 400, 401, 403, 404, 500 and 503, plus 200. Dedicated GET MockMvc tests cover invalid IDs, missing/invalid credentials, insufficient scope/ownership, unexpected code errors and database/cache failures.

A snapshot of the latest JaCoCo HTML report is included at `coverage/index.html` in this ZIP.

## Domain package layout

Custom exceptions are in `account_management/domain/exceptions`. OffensiveNicknameValidator and its corresponding unit test are in `account_management/domain/validator`. Services remain in `domain/service`.

## Project folder and build name

Extract the ZIP and open the `account-api` folder (containing build.gradle and settings.gradle) in IntelliJ. Gradle rootProject.name and spring.application.name are both `account-api`. The executable JAR is `build/libs/account-api-0.0.1-SNAPSHOT.jar`:

```powershell
java -jar build/libs/account-api-0.0.1-SNAPSHOT.jar --spring.profiles.active=local
```

Java packages remain `com.westpac.assessment`, matching the source directory and existing imports. The database name and JWT issuer/audience retain their existing values, so existing database connections and tokens remain compatible. No new dependency coordinates are required for the folder rename.

Docker Compose derives its project/volume prefix from the folder name. If you already created your local PostgreSQL volume from the previous `assessment` folder, use `docker compose -p assessment up -d` to reuse that Compose project, or point DB_URL at your existing database. A new Compose project uses a different volume. If your original Compose project had a different name, use that exact name instead.

## Constants, formatting and application logging

- `constants/ApiConstants.java` centralises API paths, header names and scopes. `constants/ErrorMessages.java` centralises repeated public error messages. `src/test/java/.../TestConstants.java` holds shared test fixtures; it contains no deployment credentials.
- The reported duplicate string literals were extracted, restricted-name local variables were renamed, multiple declarations were split, and concurrency-test loop boundaries made explicit. Java source was formatted consistently. The nickname validator uses a linear Unicode character walk rather than regex replacement.
- `RequestLoggingFilter` emits only a server-generated request ID, an allow-listed HTTP method, a fixed operation name, HTTP status and duration. It returns `X-Request-Id` and adds the same generated ID to MDC for correlating fixed error events. It never logs incoming request IDs, URL IDs, query strings, headers, JWTs, API keys, idempotency keys, customer names/nicknames, account data, bodies, or exception messages/stack traces.
- `AccountExceptionHandler` logs fixed event codes instead of Throwable objects; this avoids leaking SQL values or upstream response bodies through exception messages. SQL/bind/extract/error logging categories are disabled in the common configuration. These controls cover application-owned logs; infrastructure/APM and external logging configuration should apply the same policy.
- `RequestLoggingFilterTest` verifies sensitive-marker exclusion, generated IDs, MDC cleanup/restoration and safe exception handling. `SafeExceptionLoggingTest` checks that exception messages, SQL-like values and upstream bodies are not logged.
- `.gitignore` excludes `/coverage/`, build output and local environment secrets. To remove already tracked report files, run `git rm -r --cached coverage` in your repository.
- Rerun your IntelliJ SonarLint/SonarQube inspection after importing this update. The screenshots were the warning source; a Sonar engine was not available here, so the checks performed are compilation, automated tests, formatting and a repeated-literal source scan rather than a certified Sonar issue count.
