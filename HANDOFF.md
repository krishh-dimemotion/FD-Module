# FD module implementation handoff

Implemented on top of `ac01a391fc1534527f1aeaa335616252bc2c86ea` from the supplied repository. Project folder: `FD-Module` inside this archive.

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
