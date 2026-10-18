# Patient Registration (Spring MVC + JSP + PostgreSQL + ClickHouse reporting)

Java 21 · Spring MVC 6.2 · Spring Security 6.4 · Tomcat 11 · PostgreSQL 18 · ClickHouse 25.3 · Kafka 3.9 + Debezium 3.0 (optional) ·
React 18 analytics UI · Apache POI (Excel) · OpenPDF (PDF). Everything is open source and runs in Docker.
Several hospitals share one installation; each sees only its own data.

Full documentation (local HTML): `Docs/Patient-Registration-Project-Guide.html`, `Docs/Reports-Comparison-JSP-vs-Analytics.html`,
`Docs/Stage-5-Completion-Report.html`, `Docs/Final-Review-Report.html`, `Docs/Solution-Architecture.html`, `Docs/SQL-Server-Impact-Analysis.html` (what changes with Microsoft SQL Server), `Docs/ClickHouse-and-New-Tools-Explained.html` (how ClickHouse stores data, and every new tool with an example).

## Run (only Docker Desktop is needed)
| Step | Command |
|------|---------|
| Start everything (first run builds the app image, ~3 min) | `start.bat` or `docker compose up -d --build` |
| Start with real-time reports (Kafka + Debezium) | `start.bat cdc` or `docker compose --profile cdc up -d --build` |
| Rebuild/redeploy the app after a code change | `deploy.bat` or `docker compose up -d --build app` |
| Stop (data is kept in Docker volumes) | `stop.bat` or `docker compose --profile cdc --profile seed stop` |
| Load test patients (default 1 lakh, hospital 1) | `load-data.bat [rows] [hospital]`, e.g. `load-data.bat 100000 1`, `load-data.bat 20000 2`, `load-data.bat 10000 3` |
| Full backup (schemas identity, patientreg, reporting) | `backup.bat` -> `backup/patientreg_full_YYYY-MM-DD_HHmm.dump` |
| Restore a backup (replaces all data, reloads ClickHouse) | `restore.bat backup\<file>.dump` |

App: http://localhost:8080/patient-registration/ · Analytics UI: http://localhost:8080/patient-registration/app/

Sign in: `admin` / `Admin@1234` (super administrator of all hospitals). Demo users, password `Demo@1234`:
`h001.admin`, `h002.admin` (hospital administrators), `h001.reg`, `h002.reg`, `h003.reg` (registration desk),
`h001.reports`, `h002.reports` (reports), `group.reports` (reports at all three hospitals).
The demo hospitals H002/H003 and these users are created at start because `.env` has `DEMO_DATA=true` (set `false`
for an empty installation). Patients: `load-data.bat` (above), or restore the demo data of this machine:
`restore.bat backup\patientreg_full_2026-10-16_after-stage5.dump` (1,00,002 / 20,000 / 10,000 patients).

Passwords and the seed size are in `.env` (local POC values, plain text). Ports: 8080 app (reachable from the network);
5432 PostgreSQL, 8123 ClickHouse HTTP, 8083 Debezium REST (cdc only) on 127.0.0.1 only.

### Containers (`docker-compose.yml`, project `patient-registration`)
| Service | What it does | Memory cap |
|---------|--------------|-----------|
| `postgres` (pr-postgres) | live database; `wal_level=logical`, 256 MB `/dev/shm` | 700 MB |
| `clickhouse` (pr-clickhouse) | analytics copy (`analytics`, test db `analytics_test`) | 1.2 GB |
| `app` (pr-app) | Tomcat 11 + the WAR, built from source by the `Dockerfile` | 900 MB |
| `pg-setup` | one-shot: test db `prtest`, accounts `reporting_ro`/`debezium`, publication | - |
| `analytics-setup` | one-shot: loads ClickHouse when empty, starts the one-minute sync | - |
| `kafka`, `debezium` (cdc) | real-time pipeline: outbox -> Kafka -> ClickHouse in a few seconds | 640 / 800 MB |
| `ch-cdc-setup`, `cdc-setup` (cdc) | one-shot: Kafka consumer tables in ClickHouse, Debezium connector | - |
| `seed`, `seed-backfill` (seed) | one-shot: bulk test data, then reload ClickHouse | - |

On first start of empty volumes the app creates its tables, `pg-setup` creates the accounts and ClickHouse is
filled automatically. Measured on this laptop with everything running: about 1.6-2 GB in total.

## Demo data on a new machine
The full demo data set (H001 1,00,002, H002 20,000, H003 10,000 patients, all users, settings and saved reports) is
in the repository: `backup\patientreg_full_2026-10-16_1726.dump` (22 MB; the older dumps in `backup\` are earlier
stages). The bundled `tools\` (Maven, Node, library cache) let a Windows machine build and test without downloads.
1. `git clone`, then `docker compose up -d --build` and wait until `docker compose ps` shows the app as healthy.
2. Optional check: `certutil -hashfile backup\patientreg_full_2026-10-16_1726.dump SHA256` must print
   `5eb7c11e3fb09084e79871119944e35fb08c0a76be5a67d5660f6bc936b9f72b`.
3. `restore.bat backup\patientreg_full_2026-10-16_1726.dump` (type YES): replaces all data and reloads ClickHouse.
4. For the real-time pipeline afterwards: `start.bat cdc`.
A new dump (`backup.bat`) committed later adds about 22 MB to the repository history each time.

## Database (PostgreSQL, 24 tables in 3 schemas)
| Schema | Tables |
|--------|--------|
| identity | hospital, app_user, app_user_hospital (role per hospital), role (built-in + hospitals' own), audit_log |
| patientreg | patient (+ custom_fields JSONB), patient_contact, patient_emergency_contact, patient_insurance, patient_medical_info, patient_visit, department, doctor, lookup_value, form_field_setting, custom_field_def, hospital_reg_counter, report_outbox, cdc_heartbeat; view v_patient_report |
| reporting | saved_report, dashboard, calculated_field, field_setting, export_setting |

Every patient row carries `hospital_id`; row-level security shows the app only the signed-in user's hospitals.
Registration numbers per hospital and year: `H001-REG2026` + 7 digits.

## Screens
- **Dashboard** – counts of the working hospital (switch hospital in the header)
- **New Registration** – 6-section form with the hospital's own fields, lists, departments and doctors; server-side validation
- **Report 1: Patient Register**, **Report 2: Department Summary** – the classic JSP reports with Excel/PDF
- **Analytics** (`/app/`) – self-service report builder on ClickHouse: any columns, filters, totals, calculated fields,
  % of total / rank / running total, subtotals, top N, pivot, highlighting, drill-down, charts, dashboards,
  private or shared reports, Excel/PDF/CSV downloads. Never touches the live database.
- **Settings** – form fields, dropdown lists, departments & doctors, report field rules, download branding and formats
- **Administration** – users, hospitals, roles, audit log

## Bulk data
- `db/load-test-data.sql` fills **every column of all six patient tables** with realistic, consistent data for one
  hospital. Current demo data: H001 1,00,002, H002 20,000, H003 10,000 patients.
- Bulk SQL bypasses the outbox, so the seed profile reloads ClickHouse afterwards (`infra/clickhouse/pipeline/backfill.sql`).
- Downloads: at most `export.maxConcurrent` (default 3) run at once; extra requests wait up to 20 s, then get "server busy".

## Reporting data flow
1. Every registration writes a PatientSnapshot event into `report_outbox` in the same transaction.
2. Default mode: ClickHouse copies the last 15 minutes of `v_patient_report` every minute.
3. `cdc` profile: Debezium reads the outbox from the WAL -> Kafka topic `pr.patient.report` -> ClickHouse Kafka engine
   -> `analytics.patient_report` (a few seconds). Heartbeats drive the "data as of" time. Malformed messages
   land in `analytics.pipeline_errors`. If Debezium, Kafka or ClickHouse is down, registration keeps working.
4. Only the hospitals' own fields that are marked for reports leave PostgreSQL.

Reporting API (JSON, `/api/v1`): `me`, `datasets`, `query`, `query/export`, `datasets/{id}/fields/{field}/values`,
`freshness`, `reports`, `dashboards`, `formulas/validate`, `calculated-fields`.

## Development (on the host)
Maven multi-module build producing one WAR: `pr-shared`, `pr-identity`, `pr-registration`, `pr-reporting`, `pr-ui` (React), `pr-app`.
`build.bat` / `test.bat` use the JDK and Maven from `env.bat`; tests connect to the Docker databases on localhost.

| What | Command |
|------|---------|
| All automated Java tests (178: unit, integration, isolation, customisation, final-review regressions, architecture) | `test.bat` (containers running) |
| Browser end-to-end (23, Playwright in Edge; app container running) | `cd pr-ui` then `..\tools\node\npx playwright test` |
| Re-apply ClickHouse DDL | `bash infra/clickhouse-schema.sh` |
| Upgrade an existing ClickHouse to stage 5b | `bash infra/clickhouse/migrations/2026-10-16_stage5b_custom_fields.sh` |
