# Currency, demo tax, and PDF implementation handoff

Working checkout: `/Users/krish/src/FD-Module`.

## Current delivery

- Exact currency input/output and presentation for JPY (0), INR/USD (2), and BHD/KWD (3); monetary API responses use decimal strings and UI money uses string/BigInt formatting.
- `DECIMAL(20,4)` monetary storage, strict principal precision validation, currency-specific GL accounts, and explicit repeatable historical migration with backup and refusal on ambiguous history.
- Configurable DEMO withholding: 10% by default, zero annual allowance, primary-customer/currency/FY aggregation, exemptions, frozen rule snapshots, unique settlement assessments, and atomic linked TDS/GL postings.
- Tax eligibility on new/renewed deposits; all five original demo contracts remain tax-disabled. Net compounding, periodic cash payouts, withdrawal quotes/execution, maturity, and renewal share the settlement arithmetic.
- All six reports support PDF using PDFBox 3.0.8 and licensed embedded fonts; CSV/JSON stay available. UI downloads use the last submitted filters. Reports remain read-only.
- INR exact grouping plus lakh/crore summary labels; international grouping plus million/billion summaries elsewhere. Demo FX includes JPY/BHD/KWD.
- README, OpenAPI, migration tools, verification scripts, and MANUAL-COMPARISON.md updated.

## Verification

- FD Maven suite: **141 passed**, zero failures/skips (129 existing plus 12 new currency/tax cases).
- Report/FX/PDF Maven suite: **24 passed**, zero failures/skips, including all six PDF endpoints, multipage extraction, and unchanged database snapshots.
- Gateway and money formatter Node suites: **8 passed**. Binary PDF bytes and attachment headers are checked through the gateway.
- All **7 live gateway probes** passed. OpenAPI schema validation passed, and live responses from all six reports plus every account in both stacks and three-decimal FX validated against the contract.
- Existing local database migration backed up five accounts and retained all 13 transactions/38 GL entries and tax-disabled eligibility. The main database still contains exactly five accounts, with zero tax-enabled contracts.
- A disposable stack exercised five currencies, very large three-decimal amounts, net withdrawal/renewal, positive recorded tax, accrual rerun idempotency, all JSON/CSV/PDF reports, and read-only report behavior.
- Disposable historical migration verification passed: unrecognized accrual history was refused; deliberately merged foreign GL history was attributed correctly; values, eligibility, and assessments survived; a second migration preserved the same state.
- Per-currency SQL checks passed for balanced GL groups, deposit and accrued liabilities, account ledger balances, closed balances, renewal roles, withholding payable, and assessment totals. Closure reconciliation including tax on external payouts passed.
- The reporting database user still rejects UPDATE statements.
- BrowserOS verified exact mixed-currency rows and compact labels, a real downloaded PDF, populated demo tax reporting, and a 390px layout without document overflow. Currency inputs use steps 1 / 0.01 / 0.001; JPY/INR excessive fractions fail native validation while KWD three-decimal input is accepted.
- All six sample PDFs were rendered and visually reviewed. Local examples are in ignored `output/pdf/`; backup SQL files remain private in ignored `output/backups/`.

## Reproduce

```sh
./scripts/verify.sh
node scripts/migrate-money.mjs --check
```

For existing databases, run `node scripts/migrate-money.mjs --apply` before starting updated services. Read README for configuration and migration recovery. `scripts/verify-upgrade.mjs` and `scripts/verify-money-migration.mjs` require an explicitly isolated fixture database and must not be pointed at the normal demo database.

The original handoff below records yesterday's baseline. Its boundaries describe that earlier delivery, before this upgrade.

---

# FD module implementation handoff

Implemented on top of `ac01a391fc1534527f1aeaa335616252bc2c86ea` from the supplied repository. Working checkout: `/Users/krish/src/FD-Module`.

## Delivered

- Separate Spring Boot JDBC report service with SELECT-only production database credentials, repeatable-read queries, six report types, JSON, stable CSV sections, customer filters, and account statements.
- Dated configurable DEMO exchange rates and decimal conversion, served by the new service without external calls.
- Node gateway on `/fd/v1`, request/response forwarding, browser CORS, bounded upstream requests, structured failures, and live routing checks.
- Reports page with filter controls, readable report columns, currency totals, warnings, empty/error states, CSV download, and demo FX conversion. All existing pages link to Reports and preserve an API-base override.
- Docker Compose runtime, generated ignored local credentials, dedicated DB users, isolated test schemas, readiness checks, verification and synthetic-fixture commands.
- Updated OpenAPI and README.

Existing FD maturity, renewal, and ledger business code was preserved. One existing configuration test now excludes host environment property overrides so it can test local-file/profile precedence independently of Docker credentials. Normal Tomcat starts successfully under Java 21 in Docker; no Windows NIO2 workaround was added.

## Validation

- Existing FD suite: **129 tests passed**, none skipped.
- Reporting/FX suite: **21 tests passed**, none skipped (including real MySQL API integration tests).
- Gateway suite: **4 tests passed**.
- All six live gateway probes passed, including CSV and application error statuses.
- Production reporting user rejected an UPDATE statement.
- Synthetic account creation, withdrawal, maturity payout/rerun, linked renewal, report/CSV reads, and FX conversion passed. Report reads left account details, customer transactions, GL balances, and the clock unchanged.
- SQL checks passed for balanced GL groups, deposit liability, accrued interest payable, customer ledger balances, closed balances, and copied renewal role counts.
- BrowserOS checks verified report generation, customer statements, growth, empty TDS results, demo FX, API-base navigation, active/matured/renewed operations lookups, and a real downloaded CSV whose row amounts reconciled to its total. Mobile width 390px had no page overflow.
- The complete OpenAPI document passed schema validation; all six report response types and both FX response types validated against their live responses. Two existing YAML flow descriptions were quoted to make the old withdrawal schemas valid.
- Fresh MySQL initialization created all three expected schemas, including when the initialization script was sourced rather than executed. The temporary initialization-check project and its volume were removed.
- The existing optional withdrawal browser script now accepts configurable gateway/frontend bases; it was not run with Playwright because browser verification used BrowserOS.

See README for the exact commands. Generated test data, screenshots, and fixture manifests belong in ignored `output/`; credentials remain in ignored `.env`.

## Boundaries

This is the agreed open-access local lab demo. No JWT integration, live FX provider, statutory tax calculation/certification, notifications, or PDF export was added. TDS reports reflect recorded deductions only. Customer filters do not authenticate the requester. Shared existing GL codes are configured as INR; mixed-currency reports stay separate and do not claim currency-specific GL reconciliation.
