# Fixed Deposit Module

Spring Boot / Java 21 / MySQL fixed-deposit operations, with a separate read-only report service, dated demo exchange rates, and a local API gateway. This is a local lab demo with open access; customer filters are operations filters, not authenticated identity.

## Run locally

Requires Docker with Compose and Node.js 24 or newer. Java, Maven, and MySQL run in containers.

```sh
node scripts/setup.mjs
./scripts/compose.sh up -d --wait
```

Open **http://localhost:3000/reports.html** for reports or **http://localhost:3000/operations.html** for deposit operations. The first start downloads Java/Maven dependencies and can take several minutes. `scripts/compose.sh` supports both `docker compose` and the standalone `docker-compose` command. On Windows, run the shell commands in Git Bash or use `docker compose` directly after running the Node setup command.

| Component | Local address | Role |
| --- | --- | --- |
| Frontend | http://localhost:3000 | Account opening, withdrawal, operations, reports |
| Gateway | http://localhost:8080/fd/v1 | Public local API base |
| FD service | http://localhost:8081 | Existing account, batch, clock, and GL APIs |
| Report service | http://localhost:8083 | Reports, demo FX, health |
| MySQL | Compose network only | `fd_db`, isolated test databases |

The gateway strips `/fd/v1`, forwards FD routes to `fd:8081`, and forwards `/reports/**` and `/exchange-rates/**` to `reports:8083`. It preserves application error statuses and returns JSON `502 UPSTREAM_UNAVAILABLE` or `504 UPSTREAM_TIMEOUT` on connection failure/deadline. Its health endpoint is `/health`; the report service health endpoint checks the database and business clock.

The frontend supports `?apiBase=http%3A%2F%2Flocalhost%3A8080%2Ffd%2Fv1`, preserved across navigation. Existing FD endpoints remain available at their original unprefixed paths on port 8081. Reports should use the gateway; pointing the whole UI at port 8081 does not provide report routes.

`node scripts/setup.mjs` creates an ignored `.env` with generated local passwords and preserves an existing file. `.env.example` lists the required settings. Initialization creates an FD writer, a SELECT-only report user, and a test user restricted to `fd_db_test` and `report_db_test`. Passwords are applied when the MySQL volume is first initialized; editing `.env` later does not rotate existing users. New installations create tables from the current FD entities; the older SQL exports are not imported automatically. MySQL uses case-insensitive table names to support the existing schema conventions across Windows and Linux.

The Compose demo starts its business clock at **2026-01-01** on an empty database and disables the nightly scheduler so fixtures stay deterministic. Existing production defaults in the FD service are unchanged. Tomcat uses its normal connector; the Windows startup issue did not reproduce in Docker.

```sh
./scripts/compose.sh logs --tail=50 fd reports gateway
./scripts/compose.sh stop
./scripts/compose.sh up -d --wait
```

Stop/start preserves the database volume. After changing Java source, restart the affected service to compile and package it again: `./scripts/compose.sh restart reports`. The repository Maven wrapper in `backend/fdservice` also builds reports with `sh mvnw -f ../reportservice/pom.xml clean verify` from that directory.

## Reports

`GET /fd/v1/reports/fd-accounts?reportType=...` accepts optional `custId`, `accountId`, `fromDate`, `toDate`, and `format=json|csv`. `ACCOUNT_STATEMENT` requires a UUID `accountId`. Customer filtering matches the account's primary customer or an OWNER role and deduplicates matches; nominees and other non-owner roles do not grant ownership.

| Type | Dates and calculation |
| --- | --- |
| `PORTFOLIO_SUMMARY` | Current account details. Dates filter account opening dates, not historical account state; default is all opening dates through the business clock. |
| `TOTAL_LIABILITY` | Current ACTIVE deposit balances plus per-account rounded accrued interest. Rejects date filters. The position is stored state, not a new accrual calculation. |
| `MONTHLY_GROWTH` | Default: twelve calendar months ending at the business date, with a partial final month. Opening is the prior day's posted balance; closing is the selected interval end. Shows net/percentage change, external new deposits, closure payouts, renewal transfers, and opened/closed counts. |
| `TDS_COMPLIANCE` | Actual TDS transaction rows by posting date. Defaults to April 1 through the business date; financial years run April–March. Debit deductions are positive, credit reversals negative. Totals group customer, financial year, and currency. |
| `MATURITY_SCHEDULE` | Currently ACTIVE accounts whose maturity dates fall within the range. Defaults to business date through twelve months ahead. Booked maturity amounts are projections. |
| `ACCOUNT_STATEMENT` | Default: account opening through the business date. Shows transactions in posting-date/transaction-ID order, opening/closing balances, and a running deposit balance. |

Bounds are inclusive. Only maturity schedules accept `toDate` after the business clock. Historical balances sum `FDT_BAL_AFTER - FDT_BAL_BEFORE` by `FDT_TXN_DT`, rather than summing signed transaction amounts: SIMPLE interest paid externally has zero effect on the deposit balance. Renewal transfers cancel across a complete portfolio and are excluded from new-deposit inflows. Monthly opened/closed counts include renewal account openings/closures; renewal monetary flows are separate. Percentage growth is `100 * (closing - opening) / opening`, rounded to four decimals, or null when opening is zero. Monthly balances exclude interest not yet posted to the customer ledger; current total liability includes rounded unpaid accrued interest.

The report service uses JDBC parameter binding and a repeatable-read, read-only transaction. It reads FD snapshots and never calls Customer/Product services or writes to FD tables. Monetary values are decimal **strings**, with totals kept separate per currency. Currency precision comes from the account snapshot, falling back to the ISO currency precision when absent. Missing balance snapshots, broken balance chains, or missing account history produce `422 INCOMPLETE_LEDGER_HISTORY`; current balance gaps produce `422 INCOMPLETE_ACCOUNT_BALANCE`. Missing display or TDS PAN/rate snapshots are returned as warnings. Unavailable data produces `503 REPORT_DATA_UNAVAILABLE`.

TDS reporting does not calculate or post deductions, certify taxes, or produce statutory certificates. The current FD workflow does not generate TDS rows, so an empty compliance report is expected until another workflow supplies recorded deductions. Tests use explicit synthetic TDS records.

JSON contains `reportType`, `generatedAt`, `businessDate`, effective dates, `rows`, `totals`, and `warnings`. CSV uses the same calculations and includes metadata columns plus a `section` column (`ROW`, `TOTAL`, `WARNING`, or `METADATA` for empty results). Schemas and column order are stable per report type. Values use standard CSV quoting and neutralize spreadsheet formulas in text while preserving signed decimal amounts. Downloads use the last submitted report filters.

The existing GL registry shares configured INR ledger codes even when FD accounts use another currency. Reports therefore never convert totals using demo FX or claim per-currency reconciliation against these shared GL balances. The supplied reconciliation fixture uses INR only.

## Demo exchange rates

```text
GET /fd/v1/exchange-rates?base=USD&date=2026-02-02
GET /fd/v1/exchange-rates/convert?from=USD&to=INR&amount=100&date=2026-02-02
```

Rates are invented local samples, clearly labeled `source: DEMO`, initially covering INR, USD, EUR, and GBP. They do not affect deposits, posting, settlement, or report totals. Both endpoints default to the business clock date; an explicit future date can use the most recent applicable local set. Responses include requested and effective dates.

`backend/reportservice/src/main/resources/demo-rates.properties` stores currency units per one INR, keyed as `YYYY-MM-DD.CCY`. Selection uses the latest rate set on or before the requested date; a future set is never substituted. All sets must have the same currencies and INR=1, with positive values. A custom file can be selected through `FX_RATES_LOCATION=file:/workspace/backend/reportservice/config/demo-rates.properties` in `.env`, then recreating reports with `./scripts/compose.sh up -d --force-recreate reports`.

Cross-rates use decimal division. Quotes expose twelve decimal places; conversion uses the unrounded ratio, then rounds HALF_UP to the target currency's minor units. Negative amounts, invalid currencies/dates, or excessive precision return 400; unsupported configured currencies or dates before the first set return 404. Input precision is limited to 30 significant digits and scale -12 through 12.

## Verify

```sh
./scripts/verify.sh
```

This starts/checks the stack, runs the existing FD and new report/FX Maven suites against isolated test databases, runs gateway tests, performs six read-only live route probes, and confirms the production report database user cannot UPDATE tables. It does not erase or populate `fd_db`. Test users cannot access the development schema. Java tests run sequentially because existing FD tests share `fd_db_test`.

For synthetic lifecycle fixtures on this project's **empty isolated local database**:

```sh
FD_E2E_ISOLATED_DB=1 ./scripts/verify.sh
```

The extra workflow creates five INR accounts including linked renewal, performs withdrawal and maturity, tests maturity rerun idempotency, exercises all reports/CSV and demo FX, compares state before/after report reads, and runs read-only SQL reconciliation. The fixture manifest lives in ignored `output/demo-fixture.json`; subsequent runs verify the same accounts. A nonempty database without a matching manifest is rejected. Do not use this option on a shared database: it controls the business clock and posts synthetic deposits.

Read-only gateway checks can run independently:

```sh
node scripts/check-gateway.mjs
node scripts/check-gateway.mjs --json
```

Set `FD_API_BASE` for another gateway URL. Gateway unit tests cover method/query/body/CSV forwarding, CORS, error pass-through, invalid routes, disconnected upstreams, and deadlines. Report tests cover customer-role filtering, date defaults/boundaries, growth/payout/renewal arithmetic, statements, financial-year TDS/reversals, missing snapshots, CSV escaping, FX cross-rates/date selection/rounding, and unchanged database contents.

The updated contract is [fd_module_openapi.yaml](backend/fdservice/fd_module_openapi.yaml). The implementation handoff and validation results are in [HANDOFF.md](HANDOFF.md).
