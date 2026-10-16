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

`GET /fd/v1/reports/fd-accounts?reportType=...` accepts optional `custId`, `accountId`, `fromDate`, `toDate`, and `format=json|csv|pdf`. `ACCOUNT_STATEMENT` requires a UUID `accountId`. Customer filtering matches the account's primary customer or an OWNER role and deduplicates matches; nominees and other non-owner roles do not grant ownership.

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

The FD service now calculates DEMO withholding for new and renewed contracts. The report service remains read-only and summarizes recorded deductions; it does not calculate taxes or issue statutory certificates. Existing contracts retain tax-disabled eligibility. The API identifier `TDS_COMPLIANCE` is displayed as **Demo tax summary**. Engine deductions use their settlement value date for financial-year grouping, while date filters continue to use posting dates. Legacy externally supplied deductions without assessment metadata retain the April–March posting-date grouping and snapshot warnings.

JSON contains `reportType`, `generatedAt`, `businessDate`, effective dates, `rows`, `totals`, and `warnings`. CSV uses the same calculations and includes metadata columns plus a `section` column (`ROW`, `TOTAL`, `WARNING`, or `METADATA` for empty results). Schemas and column order are stable per report type. Values use standard CSV quoting and neutralize spreadsheet formulas in text while preserving signed decimal amounts. Downloads use the last submitted report filters.

GL posting is separated by currency. Configured INR codes are preserved; foreign codes receive an ISO suffix (for example `FD_DEP_LIAB_KWD`). `scripts/reconcile-demo.sql` checks balanced groups, deposit/accrued liabilities, and withholding payable per currency. Historical shared-GL entries are attributed by the explicit migration below.

## Demo exchange rates

```text
GET /fd/v1/exchange-rates?base=USD&date=2026-02-02
GET /fd/v1/exchange-rates/convert?from=USD&to=INR&amount=100&date=2026-02-02
```

Rates are invented local samples, clearly labeled `source: DEMO`, covering INR, USD, EUR, GBP, JPY, BHD, and KWD. They do not affect deposits, posting, settlement, or report totals. Both endpoints default to the business clock date; an explicit future date can use the most recent applicable local set. Responses include requested and effective dates.

`backend/reportservice/src/main/resources/demo-rates.properties` stores currency units per one INR, keyed as `YYYY-MM-DD.CCY`. Selection uses the latest rate set on or before the requested date; a future set is never substituted. All sets must have the same currencies and INR=1, with positive values. A custom file can be selected through `FX_RATES_LOCATION=file:/workspace/backend/reportservice/config/demo-rates.properties` in `.env`, then recreating reports with `./scripts/compose.sh up -d --force-recreate reports`.

Cross-rates use decimal division. Quotes expose twelve decimal places; conversion uses the unrounded ratio, then rounds HALF_UP to the target currency's minor units. Negative amounts, invalid currencies/dates, or excessive precision return 400; unsupported configured currencies or dates before the first set return 404. Input precision is limited to 30 significant digits and scale -12 through 12.

## Verify

```sh
./scripts/verify.sh
```

This starts/checks the stack, runs the existing FD and new report/FX Maven suites against isolated test databases, runs gateway tests, performs seven read-only live route probes, and confirms the production report database user cannot UPDATE tables. It does not erase or populate `fd_db`. Test users cannot access the development schema. Java tests run sequentially because existing FD tests share `fd_db_test`.

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

## Currency, demo tax, and PDF upgrade

Before starting updated services against an existing database:

```sh
node scripts/migrate-money.mjs --check
node scripts/migrate-money.mjs --apply
```

The apply command stops FD/report services, repeats preflight checks, writes a private SQL backup under ignored `output/backups/`, widens monetary columns to `DECIMAL(20,4)`, preserves legacy tax eligibility, attributes historical GL entries to currencies, reconciles balances, and starts the updated services. It is repeatable. MySQL DDL commits implicitly; if an error interrupts migration, leave writers stopped, correct the reported cause, and rerun. Backups contain customer data and stay local. Restore a backup with writers stopped using the MySQL client against `fd_db`; restore the matching pre-upgrade application before restarting if reverting the API. Migration cannot restore precision discarded by old two-decimal columns. Unknown historical accrual narratives or unreconciled balances stop the preflight.

Monetary response fields are now **decimal strings**. Clients must update their parsers; monetary requests accept strings and existing JSON numbers. Use strings for exact values. Posted amounts use ISO minor units (JPY 0, INR/USD 2, BHD/KWD 3) and HALF_UP settlement rounding. Inputs with excessive fractional precision or more than 16 integer digits are rejected. Accrual remains four-decimal internal state and is rounded for presentation. Currency codes are validated, and GL identifiers support currency suffixes.

All UI money uses the shared exact string/BigInt formatter. INR uses Indian grouping; other currencies use international grouping. Summary cards supplement exact values with lakh/crore or million/billion labels. JSON and CSV contain ungrouped amounts. The FX data remains invented DEMO data and never changes ledger amounts.

### Demo withholding configuration

New and renewed contracts snapshot `taxEnabled=true` by default; existing NULL/false values remain disabled. `fd.tax.enabled=false` disables eligibility for subsequently booked contracts without changing existing contracts. The base policy is 10% of settled interest, a zero annual allowance in every currency, April–March financial years, and no exemptions. These are lab settings, not statutory rules.

Compose accepts `FD_TAX_ENABLED`, `FD_TAX_RATE`, `FD_TAX_YEAR_START_MONTH`, and `FD_TAX_EXEMPT_CUSTOMERS` from `.env`; recreate FD after changing them (`./scripts/compose.sh up -d --force-recreate fd`). Per-currency allowances and financial-year overrides can be set in the FD service's ignored `backend/fdservice/config/application.properties` file. Configure the FD service with Spring properties such as:

```properties
fd.tax.enabled=true
fd.tax.rate=10.0000
fd.tax.year-start-month=4
fd.tax.allowances.INR=1000.00
fd.tax.allowances.KWD=10.000
fd.tax.exempt-customers=CUST-DEMO-EXEMPT
fd.tax.years.2027.rate=12.0000
fd.tax.years.2027.version=DEMO-2027-v2
fd.tax.years.2027.allowances.INR=2000.00
```

The primary customer, currency, and financial year share one allowance across tax-enabled deposits. Only interest above that allowance is taxed; crossing it does not tax the allowance retrospectively. Rules and exemption status freeze for that customer/currency/year at its first settlement, including a zero-deduction assessment that consumes allowance. Fiscal calendar changes are rejected after assessments exist. Use future financial-year overrides for later policy changes. Zero-interest accrual creates no assessment. Engine financial years follow **settlement value dates**, so a catch-up batch and daily processing assign the same boundaries to the same year.

Tax is assessed at interest capitalization, periodic cash payout, and final settlement during withdrawal, maturity, or renewal. Interest is posted gross, followed by a linked TDS debit and credit to `FD_TAX_PAYABLE` in the same currency/transaction. Tax on capitalization reduces the deposit's next compounding base; tax on an external interest payout leaves deposit principal unchanged. Penalties are assessed separately against gross lifetime interest and do not reduce this demo tax basis. There is no retrospective tax backfill, remittance workflow, PAN-based rate logic, or statutory certificate.

`FD_TAX_YEARS` serializes allowance consumption and retains policy snapshots. `FD_TAX_EVENTS` records one assessment per source interest transaction, even when deduction is zero. Posted TDS rows include the rate; the assessment supplies policy version, taxable amount, and financial year to reporting. No PAN is invented. Per-account locks, tax-year locks acquired before GL locks, and unique source-interest references protect retries and concurrent execution. Both amount and tax changes invalidate withdrawal quotes.

`matAmt` is a projected net deposit balance at maturity for tax-enabled contracts; it excludes periodic interest already paid externally. Interest previews provide gross `interestAmount`, net `interestPaidOut`, `estimatedTax`, and net `maturityValue`. Future estimates depend on other accounts' use of allowances. Existing historical quotes remain unchanged.

### PDF downloads

All six reports support `format=pdf`, alongside JSON and CSV, at the existing report endpoint. The UI's **Download PDF** and CSV controls use the last submitted filters. Every download takes a fresh read-only snapshot, so downloads generated after new ledger activity may differ from a previously displayed report.

PDFs use Apache PDFBox 3.0.8 and embedded Bitstream Vera fonts (license bundled under report-service resources). A4 landscape tables split wide rows into column bands, repeating row numbers, currencies, headers, metadata, and page numbers without removing fields. Empty results and warnings are included. Characters unavailable in the embedded font appear as explicit `[U+XXXX]` codes rather than missing glyphs. Demo tax PDFs are labeled as non-statutory. Downloads use `application/pdf`, attachment filenames, and `Cache-Control: no-store`.

See [MANUAL-COMPARISON.md](MANUAL-COMPARISON.md) for the remaining manual differences and [HANDOFF.md](HANDOFF.md) for verification evidence.
