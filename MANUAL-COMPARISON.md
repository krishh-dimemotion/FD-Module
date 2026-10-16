# Lab manual comparison

Reference: Banking-Technology-Lab-Manual.pdf, academic year 2026–2027. Page numbers below are PDF page indices starting at 1. This audit distinguishes implemented behavior from unavailable integration and optional extensions.

| Requirement | Status after this upgrade | Remaining work |
| --- | --- | --- |
| L12–L13, pp. 36–41: account setup, validation, unique numbers | Account opening, contract snapshots, initial roles, and transaction logging exist | Customer/Product clients run as permissive demo stubs; product term/rate constraints and category cap integration need the real services. Account number pattern lacks the manual's check digit. |
| L14, pp. 42–44: simple/compound interest, capitalization and payouts | Implemented with actual-day conventions, minor-unit rounding, gross interest, and demo tax settlements | Formula uses actual days, unlike the manual's simplified equal-period examples; this is documented in the calculator. |
| L15, pp. 45–48: premature closure and penalties | Atomic full closure, quote confirmation, configurable penalties, final withholding, ledger and GL postings | Ownership enforcement and real savings transfer integration remain missing. |
| L16, pp. 49–52: initial deposit logging | Implemented atomically with account creation and balanced GL entries | No additional upgrade gap identified. |
| L17, pp. 53–56: batch scheduling and statements | Interest batch, retry/skip handling, business clock, and on-demand statements exist | Scheduled persisted statements are missing. Daily accrual is GL-only rather than a customer transaction every day. Compose disables cron for deterministic demos. |
| L18–L19, pp. 57–63: maturity and notifications | Maturity closure and linked customer-initiated renewal exist; tax is settled before payout/renewal | Notifications and actual external savings transfers remain missing. Settlement uses an internal clearing GL. |
| L20, pp. 64–66: lifecycle integration | Automated tests exercise opening, interest, withdrawal, maturity, renewal, ledger, reporting, tax, and currencies | Full-system ownership checks and notifications require other modules. |
| L21, pp. 67–69: reports and secure integration | Six JSON/CSV/PDF reports, customer filters, portfolio/statement/maturity information, read-only reporting credentials | Filters are not authentication. JWT and role enforcement remain missing. Report shapes/routes differ from the manual's sample product-level summary and dedicated interest-history endpoint. PDF is optional in the manual and implemented here as requested. |
| L22, pp. 70–71: documentation and tests | README, OpenAPI, handoff, migration instructions, and automated tests supplied | Full integrated banking solution document and presentation remain separate work. |
| L23–L25, pp. 72–74: Azure/cloud execution | Local Docker Compose runtime and health checks exist | Azure/Kubernetes deployment, scaling, and full cloud validation remain missing. |
| Earlier login/customer/product/calculator labs | External module contracts/stubs are present | This repository does not implement the entire login/customer/product platform or standalone calculator workflow. UI is plain HTML/JS and reporting is Java rather than the manual's Angular/Python examples. |
| Taxation and currency presentation | Configurable DEMO withholding plus precise JPY/INR/USD/BHD/KWD amounts, currency-specific GLs, grouping and summary labels | The manual provides no tax policy. The implemented 10% default and allowances are lab policy, not legal compliance. |

This upgrade addresses taxation, PDF reports, and currency accuracy. It does not claim full conformance to every module in the manual.
