# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

Spring MVC 6.2 (plain Java config, no Spring Boot, no ORM) + JSP patient registration app on Java 21 / Tomcat 11, PostgreSQL 18 in Docker. A 70-field registration form is stored across 6 tables plus a `department` master; two reports (patient register, department summary) render on screen and download as Excel (Apache POI) or PDF (OpenPDF). Three hospitals: H001 1,00,002 patients, H002 20,000, H003 10,000 (browser tests rely on these counts — remove any test patients you add). Human-facing documentation in `Docs/` — keep in sync when behaviour, schema, config or counts change: `Patient-Registration-Project-Guide.html` (v3), `Reports-Comparison-JSP-vs-Analytics.html` (old vs new reports, for business readers), `Stage-5-Completion-Report.html`, and the design `Solution-Architecture.html`. The user asked that every new document set also gets/updates the comparison document. Roadmap stages 0–5b are done (5a login + multi-hospital isolation, section 10.1 / D13; 5b full customisation — own form fields, per-field report rules, formulas, pivot/subtotals/top N, dashboards, sharing, branded downloads, custom roles, section 10.2 / D14). Stage 6 (load test) is postponed by the user.

## Environment (local Windows machine)

- **Everything runs in Docker** (`docker-compose.yml`, project `patient-registration`): `pr-postgres`, `pr-clickhouse`, `pr-app` (Tomcat 11 + WAR built by the root `Dockerfile`), one-shot `pg-setup` / `analytics-setup`; profile `cdc` adds `pr-kafka`, `pr-debezium`, `ch-cdc-setup`, `cdc-setup`; profile `seed` adds `seed`, `seed-backfill`. Passwords in root `.env`. App at http://localhost:8080/patient-registration/. The user wants the project shareable with nothing installed but Docker.
- The pre-Docker containers `Postgres` (postgres:latest) and `pr-clickhouse-old` are stopped and kept (user decision: stop, not delete). Host Tomcat `D:\Pravin\apache-tomcat-11.0.27` is no longer used (port 8080 belongs to `pr-app`).
- For host builds/tests: JDK `D:\Pravin\jdk-21.0.12.1` (not on PATH; `JAVA_HOME` in the shell points elsewhere — always set it); Maven 3.9.9 and its local repo are bundled in `tools\` (`tools\apache-maven-3.9.9`, `tools\m2repo`). Do not use `D:\Pravin\apache-maven-3.10.0`.
- PostgreSQL: db `postgres`, schema `patientreg`; settings in `pr-registration/src/main/resources/db.properties`, overridden in the container by env vars (`DB_URL` -> `db.url`, `APP_REPORTING_CLICKHOUSE_URL` -> `app.reporting.clickhouse.url`; Spring's system-environment property source wins over `@PropertySource`). Tests use database `prtest` (`pr-app/src/test/resources/db.properties`) and ClickHouse `analytics_test`, via localhost ports.
- Low-RAM machine (7.7 GB, Docker ~3.9 GB): every service has `mem_limit`; `MAVEN_OPTS=-Xmx512m`. The user asked not to run load tests for now.

## Commands

Scripts: `start.bat [cdc]`, `stop.bat`, `deploy.bat` (rebuild app image), `load-data.bat [rows] [hospital]`, `backup.bat`, `restore.bat <dump>` wrap docker compose; `build.bat`, `test.bat` run host Maven. From the Bash tool (run .bat files as `cmd //c "D:\\Pravin\\patient-registration\\x.bat"`; a relative name is not found):

```bash
# whole system / real-time pipeline / redeploy app after code change
docker compose up -d --build
docker compose --profile cdc up -d --build
docker compose up -d --build app
docker compose logs -f app
curl -s http://localhost:8083/connectors/patient-outbox/status          # Debezium connector (cdc)

# build (add -o for offline once plugins are cached; `clean` needs the clean plugin downloaded once)
cd /d/Pravin/patient-registration && JAVA_HOME="D:/Pravin/jdk-21.0.12.1" \
  ./tools/apache-maven-3.9.9/bin/mvn -q -B -Dmaven.repo.local="D:/Pravin/patient-registration/tools/m2repo" clean package
# (package runs the tests too; add -DskipTests to skip)

# tests: all, one module, or one class
mvn ... test
mvn ... -pl pr-registration test
mvn ... -pl pr-app -am -Dtest=WebSmokeTest -Dsurefire.failIfNoSpecifiedTests=false test

# ClickHouse (admin user "admin", password in .env)
bash infra/clickhouse-schema.sh                     # idempotent DDL into analytics + analytics_test
docker exec -i pr-clickhouse clickhouse-client --user admin --password ChAdmin@1234 --multiquery < infra/clickhouse/pipeline/backfill.sql
docker exec pr-clickhouse clickhouse-client --user admin --password ChAdmin@1234 -q "select count() from analytics.patient_report FINAL"

# React UI (pr-ui): built by Maven via frontend-maven-plugin (Node in tools/node). For fast UI work:
cd pr-ui && PATH="/d/Pravin/patient-registration/tools/node:$PATH" npm run dev     # proxies /api to localhost:8080
# browser end-to-end tests (Playwright, installed Edge; pr-app must run the current code; expect 1,00,002 patients)
cd pr-ui && PATH="/d/Pravin/patient-registration/tools/node:$PATH" npx playwright test

# DB access
docker exec pr-postgres psql -U postgres -c "select count(*) from patientreg.patient"

# bulk data (adds rows; then reload ClickHouse) and full backup
SEED_ROWS=100000 SEED_HOSPITAL=1 docker compose --profile seed run --rm seed && docker compose --profile seed run --rm --no-deps seed-backfill   # = load-data.bat 100000 1
docker exec pr-postgres pg_dump -U postgres -d postgres -n identity -n patientreg -n reporting --extension=pg_trgm -Fc -Z 6 > backup/patientreg_full_YYYY-MM-DD.dump
# restore: restore.bat backup\<file>.dump  (drops the 3 schemas, pg_restore --no-owner --no-acl, empties ClickHouse, docker compose up -d)
```

Tests (JUnit 5, AssertJ, Spring MockMvc, ArchUnit) need the `pr-postgres` and `pr-clickhouse` containers running and use the `prtest` (Postgres) and `analytics_test` (ClickHouse) databases. `ReportingApiTest` seeds `analytics_test` itself. `WebSmokeTest` boots the real `RootConfig` + `WebConfig` and hits every screen and download, but MockMvc does not render JSPs, so after UI changes also deploy and check pages with `curl`. No linter.

## Architecture (big picture)

- **Modular monolith, one WAR.** Maven modules: `pr-shared` (export writers `ExcelExporter`/`PdfExporter`/`ReportDefinition`, `ExportLimiter`, and `shared.tenant`: `Tenant`, `TenantContext`, `Permission`, `TenantAwareDataSource`, `AuditTrail`), `pr-identity` (stage 5a: hospitals, users, roles, Spring Security login, `TenantFilter`, audit log, admin screens; schema `identity`), `pr-registration` (all existing feature code, packages `com.hospital.patientreg.registration.{config,web,service,dao,model}`, plus `db.properties`/`schema.sql`), `pr-reporting` (new read side: `semantic` dataset catalogue from `reporting/datasets/*.json`, `query` builder/service on a read-only ClickHouse pool, `api` REST controller at `/api/v1` + problem+json advice; stage 5b: `formula`, `custom` (EffectiveCatalog, settings), `saved` (reports, dashboards), `export`, `web` (report settings screens)), `pr-ui` (React + TypeScript + Vite report builder; `npm run build` = `tsc --noEmit && vite build` into `target/classes/META-INF/resources/app`), `pr-app` (war: `WebAppInitializer`, `RootConfig`, `WebConfig`, JSPs/CSS/JS, `web.xml`, `messages.properties`; copies the pr-ui build into the WAR as plain files under `/app`). Registration and reporting must never depend on each other or on identity (they see the user only through `shared.tenant`); identity never depends on them (Maven deps + `ArchitectureTest` enforce it); only `pr-app` sees all modules.
- **Two Spring contexts.** `WebAppInitializer` boots root `RootConfig` (imports `SharedConfig`, `IdentityConfig` (+ `SecurityConfig`), `RegistrationConfig`, `ReportingConfig`; each scans only its own non-web packages; static `SchemaMigrator` bean) and servlet `WebConfig` (MVC, JSP resolver, `messageSource`; scans `registration.web`, `reporting.api`, `reporting.web`, `identity.web`, `identity.api` for controllers/advice only). Spring Security runs as `DelegatingFilterProxy("springSecurityFilterChain")` registered in `WebAppInitializer` after the UTF-8 filter. New controllers must live in a module's web/api package. `RegistrationConfig` marks the OLTP DataSource, JDBC templates and transaction manager `@Primary`, so a second data source (ClickHouse, stage 2) never makes by-type injection ambiguous.
- **Schema is applied at every startup** by `pr-app` `SchemaMigrator` (a `BeanFactoryPostProcessor`, so before any pool exists) with the administrator account `db.admin.*`: it creates/updates the application role `db.username` (normal role, NOBYPASSRLS) and runs `identity-schema.sql` -> `schema.sql` -> `reporting-schema.sql` -> `app-grants.sql` (`__APP_ROLE__` replaced), each as ONE JDBC statement batch, so `DO $$ … $$` blocks are allowed. Scripts must stay idempotent (`IF NOT EXISTS`, constraint checks in DO blocks, `ON CONFLICT`). This is the only migration mechanism.
- **One flat form bean, six tables.** `PatientForm` holds all fields; `PatientDao.insert` takes the next number from `hospital_reg_counter` (`CODE-REGyyyy` + 7 digits, per hospital and year, gap-free; `reg_no_seq` is no longer used), writes `patient` then the five child tables in one `@Transactional` `PatientService.register`. `PatientDao.SELECT_FULL` reads it back with `BeanPropertyRowMapper`, relying on column aliases that match `PatientForm` property names.
- **Registration visit invariant.** `patient_visit.registration_visit = TRUE` marks the visit created at registration (partial unique index `ux_visit_registration`). All report SQL joins on this flag (`ReportDao.REGISTER_FROM`) instead of finding the first visit — new inserts must set it.
- **Reports.** `ReportDao` has one shared FROM + dynamically built WHERE (constant fragments, bound params) used by count, page (`LIMIT/OFFSET`, 50 rows, `registration_date DESC, patient_id DESC`), streaming export, and the department summary. Row arrays are positional: on-screen rows have `patient_id` at index 0, export rows do not; `ReportService` holds the header/width arrays that must match.
- **Exports stream.** `ReportService.export*` is `@Transactional(readOnly)` so PgJDBC uses a cursor (fetch size 2000); `ExcelExporter` uses SXSSF (200-row window) and `PdfExporter` flushes an incomplete `PdfPTable` every 500 rows, both fed by `ReportDefinition`'s row-consumer. `ReportController` runs exports through `ExportLimiter` (semaphore, `export.maxConcurrent`, 20 s wait → 503) and sets download headers *inside* the limiter so a "busy" reply is not saved as a file.
- **React app at `/app`** is served by `WebConfig` resource handlers (WAR folder first, classpath fallback for tests); unknown routes get `index.html`, missing assets 404, `/app/` is a forward. It talks only to `/api/v1` (same origin, same session). Saved reports live in PostgreSQL schema `reporting` (pr-reporting's own small pool, `reporting-schema.sql`, templates upserted at startup). Downloads (`POST /api/v1/query/export`) run through the shared `ExportLimiter` and validate + count before writing headers (PDF max 50,000 rows, Excel 10 lakh, CSV 20 lakh).
- **Errors.** `GlobalExceptionHandler` (scoped to `registration.web`) renders `error.jsp`; only `ResponseStatusException` reasons are shown to users. Unmapped URLs fall through to the container error page (same `error.jsp`). The reporting API needs its own JSON (problem+json) advice.

## Rules that span several files

- **Multi-hospital isolation (stage 5a, architecture 10.1 / D13) — never bypass.** The hospital always comes from `TenantContext` (session), never from a request. Layers: (1) PostgreSQL RLS policy `hospital_isolation` on every patient table, department, `hospital_reg_counter`, doctor, lookup_value, form_field_setting, custom_field_def and all five `reporting` tables (`saved_report` has one policy per command: templates are readable, never changeable), reading `app.hospital_ids` that `TenantAwareDataSource` sets on every borrowed connection (no tenant = empty list = no rows); the app role is not a superuser, admin/migration connections are; (2) composite FKs `(hospital_id, patient_id)` / `(hospital_id, dept_id)`; (3) services check `Permission`s per hospital (`Tenant.requireHere/requireAnywhere/can`); legacy JSP screens and reports use the ACTIVE hospital, Analytics all hospitals with `ANALYTICS` (exports: `ANALYTICS` ∩ `EXPORT`); (4) `QueryBuilder` always adds `hospital_id IN (:scope_hospitals)` and `SETTINGS SQL_hospitals='..'`; the ClickHouse `reporting_app` XML row filter rejects queries without that setting. `v_patient_report` is a definer view WITHOUT RLS: only the pipeline and `publishSnapshot` (rows of the current transaction) may read it. Another hospital's record answers 404, not 403. Tests: `HospitalIsolationTest`, `AdminScreensTest`; test users in `TestUsers` (MockMvc through the real security chain). Tests checking data must use `TestUsers.adminJdbc()` — the app pool sees nothing outside a request.
- **Security plumbing.** Form login `/login` (local users, BCrypt, lock-out after `identity.login.maxFailures`), CSRF token in cookie `XSRF-TOKEN` (React sends header `X-XSRF-TOKEN`; JSP forms use `<input type="hidden" name="${_csrf.parameterName}" value="${_csrf.token}">` or Spring `form:form`), `/api/**` answers 401, everything else redirects to `/login`. Roles per hospital (`identity.model.Role`). Every security-relevant action is written to `identity.audit_log` through `AuditTrail` (append-only for the app role). First start creates super administrator `admin` / `identity.bootstrap.password`.

- **Reporting data flow (stage 2).** Registration publishes `patientreg.v_patient_report` (PatientSnapshot v1, in `schema.sql`; no Aadhaar, masked mobile, `version` = `patient.row_version`). ClickHouse `analytics.patient_report` (ReplacingMergeTree(version, is_deleted), DDL in `infra/clickhouse/schema/`) is filled by `infra/clickhouse/pipeline/backfill.sql` and kept fresh by the refreshable MV `mv_patient_report_sync` (every minute, last 15 minutes via `postgresql()`, WHERE pushed down). Adding a report field = the view + the ClickHouse DDL + both pipeline column lists + `patients.json`. Deletes and SQL-script writes are NOT propagated (neither path sees them): backfill or delete in ClickHouse manually.
- **Real-time path (stage 4, profile `cdc`).** `PatientDao.publishSnapshot` inserts the `v_patient_report` row as JSON into `report_outbox` and deletes it in the same transaction (table stays empty; Debezium reads the INSERT from the WAL via publication `pr_outbox_pub`, slot `pr_outbox_slot`). Connector config `infra/debezium/patient-outbox.json` (EventRouter -> topic `pr.patient.report`, key = patient id); ClickHouse Kafka engine + MVs in `infra/clickhouse/pipeline/020_realtime_kafka.sql` (errors -> `pipeline_errors`, heartbeats -> `pipeline_heartbeat`, used by `QueryService.dataAsOf`). Any new write path to patients must call `publishSnapshot`. `OutboxContractTest` checks the WAL event with a `test_decoding` slot. The one-minute sync keeps running in cdc mode as a safety net (duplicates collapse via ReplacingMergeTree/FINAL).
- **Reporting queries** are built only by `QueryBuilder`: field/operator/aggregation whitelisted by the catalogue, identifiers from the validated catalogue only, values always bound, `FINAL` + `is_deleted = 0` always, relative dates in IST. Never concatenate request values into SQL. The app ClickHouse account is read-only; DDL is applied by the admin via `infra/clickhouse-schema.sh`.

- **Customisation (stage 5b).** Per-user catalogue = `EffectiveCatalog` (base `patients.json` + hospital field rules from `reporting.field_setting` + own fields `cf_<hospital>_<key>` via `shared.fields.CustomFieldDirectory` + shared formulas `sf_<id>`); `QueryService.dataset()` must be the only source of fields for queries, suggestions and exports. Formulas compile only through `reporting.formula.FormulaCompiler` (allow-listed functions, text bound as params, numbers re-printed). Hospital settings screens live under `/settings` (registration: `SettingsController`; reporting: `ReportSettingsController`), need `MANAGE_SETTINGS`, and audit every change. Custom roles: `RoleAdminService` (no editing a role you hold, no granting permissions you lack). Tests: `CustomisationTest`, `e2e/customisation.spec.ts`.
- **Adding/changing a form field** touches: `schema.sql`, `PatientForm`, `PatientDao` (INSERT and `SELECT_FULL` alias), `PatientValidator` (`MAX_LENGTHS` must equal the column size; dropdowns go in `SELECT_FIELDS` + `model/Lookups`), `patient-form.jsp` (every field has a `<form:errors>`), `patient-view.jsp`, `db/load-test-data.sql` (it fills every column), and the HTML guide.
- **Server-side validation is authoritative**: dropdown values are whitelisted against `Lookups`, text lengths and numeric ranges are checked so nothing reaches the DB as a 500. `PatientController` disallows binding `patientId, hospitalId, registrationNo, status, bmi, deptName`.
- **JSP escaping**: user data must be output via `<c:out>` or Spring form tags; raw `${}` is only used for server-generated values. Every JSP starts with `<%@ include file="common/taglibs.jspf" %>` before using tags, then sets `pageTitle`/`activeNav` and includes `header.jspf`.
- Search uses `pg_trgm` GIN indexes (`first_name || ' ' || last_name`, `registration_no`, `mobile`); keep the WHERE expressions identical to the index expressions, and keep LIKE-wildcard escaping in `ReportDao`.

## Gotchas found in this environment

- Windows reports time zone `Asia/Calcutta`, which the Postgres container rejects at login; `RegistrationConfig`'s static block switches the JVM default to `Asia/Kolkata`.
- `slf4j-api` and `log4j-api` are pinned in the parent `pom.xml` (dependencyManagement); a transitive `slf4j-api` 1.7 silently turns all logging into a no-op.
- Editing a file with a shell command (sed/perl) and then using the Write tool on it fails with "modified since read"; Read it first. Beware `	` in Windows paths inside perl/sed replacements.
- Running `.bat` files via the PowerShell tool has repeatedly failed to write files and the `.bat` files themselves disappeared afterwards (`deploy.bat`, `load-data.bat`, `backup.bat`). Prefer the Bash tool with direct commands; the scripts are short wrappers around the docker compose commands in README / guide section 16: recreate them from there if missing.
- `pg_dump -n patientreg` alone omits the `pg_trgm` extension and the dump will not restore; always add `--extension=pg_trgm`.
- Never serve static files from inside a jar in `WEB-INF/lib` on Windows: Tomcat keeps the jar locked and hot redeploys then leave a half-deployed app (404s). That is why pr-ui is `provided` and copied into the WAR.
- Headless Edge under Playwright crashes when a real download completes; e2e tests record the save (`recordSaves`) and read the blob instead.
- Docker's default 64 MB `/dev/shm` made 20 concurrent legacy report scans fail ("could not resize shared memory segment"); `pr-postgres` has `shm_size: 256mb`.
- The ClickHouse admin created from `CLICKHOUSE_USER` cannot use the `pg_reporting` named collection without `named_collection_control` (`infra/clickhouse/users.d/admin-named-collections.xml`).
- The ClickHouse image has no `curl`; the connector is registered from the Debezium image (`cdc-setup`). quay.io pulls sometimes fail with "server gave HTTP response to HTTPS client": just retry.
- In compose `command` blocks, `$` must be written `$$`; values with `@` in passwords are fine.
- Restoring a dump taken from the old container needs `pg_restore --no-owner --no-acl` (roles are created later by `pg-setup`).
- Right after ClickHouse restarts, the first JDBC test classes may time out ("Read timed out"); re-run.
- The interim one-minute ClickHouse sync looks back 15 minutes: after a longer ClickHouse outage run the backfill.
- ClickHouse data cannot live on a Windows bind mount (part-directory rename fails with "Permission denied", even as root); it uses a Docker volume (inside Docker's disk image on C:). Moving Docker Desktop's disk image to D: is the way to get it onto D:.
- The ClickHouse JDBC driver refuses to make a read-only account's connection read-write, so the Hikari pool must be `setReadOnly(true)` or no connection is ever created.
- Plain Spring (no Boot) serialises `LocalDate` as arrays unless `WRITE_DATES_AS_TIMESTAMPS` is disabled (`WebConfig.extendMessageConverters`).
- ClickHouse low-memory config: `background_pool_size` 4 requires lowering the merge-tree `number_of_free_entries_in_pool_*` thresholds or the server refuses to start.
- The 1-lakh generated data has some `created_at` values later today (future), so `QueryService.dataAsOf` caps the newest change at the current time.
- Sequences are not rolled back: failed saves or aborted loads leave gaps in `patient_patient_id_seq` (registration numbers come from `hospital_reg_counter` and stay gap-free).
- A non-numeric `/patient/{id}` answers 400 (`GlobalExceptionHandler` maps `TypeMismatchException`).
- Deliberately out of scope (user decision): Unicode fonts for PDF (non-Latin text prints as `???`). (Login/CSRF were added in stage 5a.)
- `List.of(...).contains(null)` throws NPE — guard optional request parameters before looking them up.
- MockMvc requests without an HTML `Accept` header used to get 401 instead of the login redirect; `SecurityConfig` now maps every non-API request to `LoginUrlAuthenticationEntryPoint` explicitly.
- RLS policy: `x = ANY ((SELECT f()))` is parsed as ANY(subquery) and fails ("integer = integer[]"); cast the sub-select: `ANY ((SELECT f())::INTEGER[])`.
- Test set-up code that connects before Spring starts must fix the Windows `Asia/Calcutta` zone itself (`TestUsers` static block).
- Tomcat 11's EL resolves Java **records** by their components only: an extra derived getter on a record (`getText()`) is invisible as `${x.text}` and throws (500). Use `${x.getText()}` or make it a component.
- ClickHouse `GROUP BY ROLLUP` with `group_by_use_nulls = 1` still returns `''` (not NULL) for rolled-up LowCardinality columns: identify subtotal rows by the `grouping()` bit mask (`__level`, last column = lowest bit), as `ResultsTable` and `ExportService.labelSubtotal` do.
- PostgreSQL JSONB arrives in ClickHouse's `postgresql()` as `Nullable(String)`: wrap in `ifNull(x, '{}')` before `JSONExtract*`. Use `toDate32*` for user dates (`toDate` clamps years before 1970).
- `v_patient_report.custom_fields` publishes only active, reportable, not hidden own fields; changing a field's exposure bumps `row_version` of the hospital's patients holding a value (`SettingsDao.republishPatientsWith`) so the one-minute sync re-copies them.
- Masking (`MASKED`) is for free text only; for list-like fields (`suggest: true`), yes/no, numbers and dates the field is hidden instead (a masked option still reveals the value).
- MockMvc does not render JSPs: after JSP changes, deploy and run the Playwright suite (it opens every settings screen).
- Never `cp` a script's temp output over a source file after `;` — a failed perl/sed still leaves an empty temp file (this emptied a Java file once). Use the Edit/Write tools.
- Final review (16 Oct 2026) rules: only the app port 8080 is published (5432/8123/8083 on 127.0.0.1); the app role has no rights on `report_outbox` (only `publish_snapshot()` writes it); the ClickHouse app account has `<constraints>` with `<min>1</min>` (0 means "unlimited" and would pass a max-only constraint); formulas are stored with field names (`CalculatedFields.canonical`), the editor shows labels; a user without EXPORT can page patient lists only to row 5,000; the legacy JSP reports apply `field_setting` through `shared.fields.ReportFieldRules`; JSPs may not use inline scripts or `on...=` handlers (Content-Security-Policy; use `data-autosubmit` / `data-confirm` / `data-print`, see `resources/js/common.js`).
- Docker: after `docker compose up -d --build ... app`, check `docker image inspect patient-registration-app:latest --format '{{.Created}}'` (once the image was not rebuilt and old JS was served). The app has a healthcheck (bash `/dev/tcp`, `CMD` not `CMD-SHELL`: the image's sh has no /dev/tcp); `pg-setup` waits for a healthy app. `DEMO_DATA=true` in `.env` creates the demo hospitals/users (`DemoDataBootstrap`). Dashboard tiles run one query at a time (the server allows 3 per user).
- Windows: binding `127.0.0.1:5432` right after the old container released `0.0.0.0:5432` once failed with "access permissions"; running `docker compose up -d` again worked.
