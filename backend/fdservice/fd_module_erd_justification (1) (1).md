# Fixed Deposit Account Module — ERD Design Justification

**Banking Technology Lab (CSF 3142) · Group 3 · Fixed Deposit Account Module**
**Schema owner:** `fd-service` → `fd_db` (MSSQL / T-SQL)
**Companion diagram:** `fd_module_erd_g3.png` / `.svg`

---

## 1. Scope and inputs

This schema covers only what Group 3 owns — nine tables in `fd_db`. It was designed
against three inputs, then trimmed through a second pass that removed anything not
directly required, in favour of a smaller schema.

| Input | What was taken from it |
|---|---|
| `FD_Module_Actions.pdf` — the 18-action analysis | The functional requirement for every table and column. Each action's "persists data" answer decides whether something becomes a column, a table, or nothing. |
| Group 1 ERD (Customer/Auth) | `CUST_ID` format, the mandated audit-field set, naming convention. |
| Group 2 ERD (Product & Pricing) | `product_code` format, and which pricing attributes get snapshotted (rate matrix, currency, frequencies, TDS). |

Group 1's diagram contains placeholder `PRODUCTS`, `FD_ACCOUNTS`, `FD_TRANSACTIONS`
boxes — those are stubs for their own diagram's readability, not the contract.

---

## 2. Design principles

**P1 — Snapshot at booking.** A fixed deposit is a contract. Rate, interest type,
frequencies, currency, and category are copied onto `FD_ACCOUNTS` when the account is
created and never re-fetched — if Group 2's rate matrix changes next month, deposits
already booked keep their original terms.

**P2 — Cross-service references are logical, not physical.** `fd_db` is a separate
database from the customer and product databases. `CUST_ID`, `FDA_PRD_CODE`,
`FDA_RATE_ID`, `FDA_CATEGORY_CD`, `FDA_CCY_CD`, `FDNT_COMM_ID` carry the exact type of
the owning module's PK but no `FOREIGN KEY` constraint — validated over REST at write
time, dashed purple in the diagram.

**P3 — Money is never floating point.** All amounts are `DECIMAL`.

**P4 — Business date ≠ system timestamp.** Batch processing and account "time travel"
both need a controllable business date, separate from the wall clock.

**P5 — Minimise stored state, but not at the cost of a stated requirement.** Values that
can be cheaply derived are not stored — except where the requirement says otherwise.
`FDT_BAL_BEFORE`/`FDT_BAL_AFTER` on `FD_TRANSACTIONS` are the deliberate exception:
technically derivable by summing prior transactions, but stored directly per an explicit
requirement, since recomputing a balance from the full transaction history on every read
is worse than keeping it current at write time.

**P6 — `report-service` reads `fd_db` directly and calls nobody.** Any field a report
must display but does not own is snapshotted (suffixed `_SNAP`) rather than fetched live.

**P7 — Full audit set on every entity**, per the lab workbook.

---

## 3. Account number format

Per the lab manual: **10 digits total** — 3-digit branch/bank code + 6-digit per-branch
sequence + 1 check digit. Stored whole as `FDA_ACCT_NUM VARCHAR(20)` on `FD_ACCOUNTS`
(20 chars leaves headroom rather than hard-locking to exactly 10).

**No separate branch-code column on `FD_ACCOUNTS`.** The branch is already encoded in
the account number itself, so a redundant `FDA_BRANCH_CD` column was removed — storing
it twice invites the two going out of sync. `FD_ACCOUNT_SEQUENCE` still exists
separately (§8) because *generating* the number needs a live per-branch counter before
the number exists; that table just isn't FK'd back onto the account row anymore.

**Uniqueness** enforced via `UNIQUE` constraint on `FDA_ACCT_NUM`.

**Check digit** is computed from the other 9 digits at generation time (e.g. mod-10 /
Luhn) — not its own column, just part of the stored string.

---

## 4. `FD_ACCOUNTS` (FDA) — the deposit contract

One row per fixed deposit. Central table; everything else hangs off it.

**PK — `FDA_ID UNIQUEIDENTIFIER`.** A surrogate, not the account number. Generated in
application code *before* the DB write, so account + roles + initial transaction can be
built in memory and persisted in one atomic block.

| Column | Type | Why |
|---|---|---|
| `FDA_ID` | `UNIQUEIDENTIFIER` | PK, app-generated |
| `FDA_ACCT_NUM` | `VARCHAR(20)` | The 10-digit human-facing number (§3). Unique, write-once. |
| `CUST_ID` | `VARCHAR(32)` | Logical FK — the account's primary owner |
| `FDA_PRD_CODE` | `VARCHAR(20)` | Logical FK to `PRODUCTS` — which product was booked |
| `FDA_RATE_ID` | `VARCHAR(20)` | Logical FK to `PRODUCT_RATE_MATRIX` — records *which* rate row produced the booked rate, so it's traceable even after the matrix row expires |
| `FDA_CATEGORY_CD` | `VARCHAR(20)` | Logical FK to `CUSTOMER_CATEGORY` — category applied at booking (senior citizen / staff / etc.), snapshotted so later eligibility changes don't reprice existing deposits |
| `FDA_CCY_CD` | `CHAR(3)` | Logical FK to `CURRENCY`. **ISO 4217** — see §7 |
| `FDA_PRINCIPAL_AMT` | `DECIMAL(18,2)` | Original deposit, immutable |
| `FDA_PRINCIPAL_BAL` | `DECIMAL(18,2)` | Current principal — diverges from the original once interest capitalises at maturity |
| `FDA_ACCRUED_INT_AMT` | `DECIMAL(18,4)` | Interest earned but not yet posted as a transaction. 4 decimals so daily accrual doesn't lose precision on rounding |
| `FDA_INT_RT` | `DECIMAL(6,4)` | **The single contracted rate.** Calculated once at booking and stored as-is — not recalculated later, and no separate "base rate before uplift" column; if you need to explain a specific rate, trace it via `FDA_RATE_ID` back to the matrix row instead |
| `FDA_INT_TYP` | `VARCHAR(20)` | SIMPLE / COMPOUND, snapshotted |
| `FDA_COMPOUND_FREQ` | `VARCHAR(20)` | How often interest is added to principal |
| `FDA_PAYOUT_FREQ` | `VARCHAR(20)` | How often interest is paid out — kept separate from compounding frequency; conflating the two is the classic FD bug |
| `FDA_DAY_COUNT_CONV` | `VARCHAR(10)` | ACT/365 etc. — the formula that turns an annual rate into daily interest. Needed so the calculator and the account module compute the same number (open item, see §14) |
| `FDA_TDS_RT` | `DECIMAL(6,4)` | Snapshot of the statutory rate in force at booking |
| `FDA_TENURE_MONTHS` | `INT` | Contracted term |
| `FDA_OPEN_DT` | `DATE` | Account creation date |
| `FDA_VALUE_DT` | `DATE` | Date interest actually starts accruing — can differ from open date (e.g. cheque clears two days later) |
| `FDA_MAT_DT` | `DATE` | Computed once at creation, **stored, never recalculated by batch** — batch only reads it to select matured accounts |
| `FDA_MAT_AMT` | `DECIMAL(18,2)` | Maturity value quoted at booking |
| `FDA_MAT_INSTRUCTION` | `VARCHAR(30)` | Payout-or-renew instruction — kept on the account since a customer can change their own standing instruction |
| `FDA_LAST_ACCRUAL_DT` / `FDA_LAST_CAPTLZ_DT` | `DATE` | Idempotency guards — stop a rerun of the EOD batch from double-accruing interest |
| `FDA_STS` | `VARCHAR(20)` | Lifecycle state — PENDING / ACTIVE / MATURED_CLOSED / MATURED_RENEWED / CLOSED (premature closure; matches code and API) |
| `FDA_CLOSURE_DT` | `DATE` | Actual closure date, distinct from `FDA_MAT_DT` (premature closure ends the account early) |
| `FDA_RENEWED_FROM_ID` | `UNIQUEIDENTIFIER` | Self-FK. Renewal creates a **new row**, chained back to the old one — the old contract's terms and history stay intact rather than being overwritten |
| `FDA_CUST_NAME_SNAP` | `VARCHAR(255)` | Report display only (P6) |
| `FDA_PRD_NAME_SNAP` | `VARCHAR(100)` | Report display only (P6) |
| `FDA_CCY_DECIMALS` | `INT` | Snapshot of `CURRENCY.decimal_places` — decides interest-rounding precision, needed by both `fd-service` and `report-service` |
| `FDA_EFCTV_DT` + audit set | | Workbook-mandated |

**Nominee.** No separate nominee column. A nominee is just another value of
`FDRL_ROLE_TYP` on `FD_ACCOUNT_ROLES` (§6) — same mechanism as owner/joint holder,
avoiding a special-case column for something that's structurally a role.

---

## 5. `FD_TRANSACTIONS` (FDT) — the money ledger

**PK — `FDT_ID BIGINT IDENTITY`.** **FK — `FDA_ID`** (enforced).

| Column | Why |
|---|---|
| `FDT_TXN_GRP_ID` | Groups multi-leg events (e.g. withdrawal + interest on premature closure) as one business event; also the join key into GL postings |
| `FDT_TXN_TYP` | DEPOSIT / INTEREST / WITHDRAWAL / PENALTY / TDS / MATURITY_PAYOUT / RENEWAL_TRANSFER |
| `FDT_DR_CR` | Explicit debit/credit direction rather than signed amounts |
| `FDT_AMT` | Posted amount, always positive |
| `FDT_BAL_BEFORE` / `FDT_BAL_AFTER` | Balance snapshot around this transaction. Stored directly on request rather than derived on read (P5) |
| `FDT_CCY_CD` | Copied from the account |
| `FDT_TXN_DT` / `FDT_VALUE_DT` / `FDT_TXN_TS` | Business date vs. effective date vs. wall-clock write time |
| `FDT_PAN_SNAP` | Customer's PAN at time of deduction — **only populated on TDS-type rows.** Replaces the separate `FD_TAX_DEDUCTION` table (§9) |
| `FDT_TDS_RT` | Statutory rate applied to *this* deduction — also TDS-row-only, and kept per-row because the statutory rate changes by financial year |
| `FDT_REMARKS` | Optional free text |
| `FDT_REVERSAL_OF_ID` | Self-FK — corrections post a contra entry, never edit/delete a ledger row |
| `FDT_EFCTV_DT` + audit set | Workbook-mandated |

---

## 6. `FD_ACCOUNT_ROLES` (FDRL) — minimal version

Kept specifically to demonstrate the feature exists, trimmed to the essentials.

**PK — `FDRL_ID BIGINT IDENTITY`.**

| Column | Why |
|---|---|
| `FDA_ID` | Enforced FK — which account |
| `CUST_ID` | Logical FK — who |
| `FDRL_PRD_ROLE_ID` | Logical FK to `PRODUCT_ROLE` — ties to G2's role config |
| `FDRL_ROLE_TYP` | OWNER / JOINT_HOLDER / NOMINEE / GUARDIAN / GUARANTOR / BENEFICIARY |
| `FDRL_IS_PRIMARY` | Marks the one holder whose `CUST_ID` also sits on `FD_ACCOUNTS`, for fast lookup |
| `FDRL_EFCTV_DT` + audit set | Workbook-mandated |

**Removed:** `FDRL_PCT_SHARE`, `FDRL_START_DT`, `FDRL_END_DT` — ownership-split percentage
and role validity windows. Cut to keep this table to "the feature exists" rather than a
full joint-ownership accounting model.

---

## 7. Currency and language — ISO standards

**Currency (`FDA_CCY_CD`, `FDGL_CCY_CD`)** — `CHAR(3)`, must hold an **ISO 4217** code
(`INR`, `USD`, …), sourced from G2's `CURRENCY` table. `FDA_CCY_DECIMALS` supports
currencies with 0, 2, or 3 decimal places (e.g. JPY = 0, INR = 2, KWD = 3) —
G2's `decimal_places` field already covers this; nothing extra needed on the FD side.

**Language (`FDNT_LANGUAGE`)** — `CHAR(2)`, holds an **ISO 639-1** code (`en`, `hi`, …)
on `FD_NOTIFICATION_LOG`, added to support notifications/UI in at least two languages.
Which second language is a product decision, not a schema one — the column just needs
to hold whatever code is chosen.

---

## 8. `FD_ACCOUNT_SEQUENCE` (FDSQ) — account number allocation

**PK — `FDSQ_BRANCH_CD`.** One row per branch; a live counter (`FDSQ_LAST_SEQ`)
incremented atomically (`UPDATE ... SET += 1`, not read-then-write) so two account
openings at the same branch never collide. This is what generates the middle 6 digits
of the account number, before the account itself exists — required per §3's format and
explicitly called out in the action spec ("per-branch sequence table, incremented
atomically"). No longer FK'd from `FD_ACCOUNTS` (§3).

---

## 9. Tax deduction — folded into `FD_TRANSACTIONS`, no separate table

Originally a standalone `FD_TAX_DEDUCTION` table. **Removed** in favour of less schema:
every TDS event is already a `FD_TRANSACTIONS` row (`FDT_TXN_TYP = 'TDS'`), so the two
fields compliance reporting actually needs — PAN at time of deduction, and the rate
applied — were folded directly onto that row as `FDT_PAN_SNAP` and `FDT_TDS_RT` (§5).

**Trade-off accepted:** no dedicated exemption-flag audit trail, and financial-year
aggregation (for TDS certificates) now happens at query time — filter
`FD_TRANSACTIONS WHERE FDT_TXN_TYP = 'TDS'` and derive the financial year from
`FDT_TXN_DT` — instead of being pre-tagged on a separate row. Acceptable trade for one
fewer table.

---

## 10. `FD_GL_ACCOUNTS` (FDGL) + `FD_GL_ENTRIES` (FDGE) — double bookkeeping

**Kept in full** — the course brief explicitly calls for internal accounts, double
bookkeeping, and GL transactions.

`FD_GL_ACCOUNTS` is a registry of the bank's internal ledger codes (one row per GL
account: `FDGL_CD` PK, `FDGL_NAME`, `FDGL_TYP` asset/liability/income/expense,
`FDGL_CCY_CD`, `FDGL_CURRENT_BAL` running total, `FDGL_IS_ACTIVE`).

`FD_GL_ENTRIES` is the postings table (`FDGE_ID` PK, FK to `FDGL_CD` and `FDT_ID`,
`FDGE_TXN_GRP_ID` shared with the customer transaction so debits can be checked against
credits per event, `FDGE_DR_CR`, `FDGE_AMT`, `FDGE_POST_DT`, `FDGE_NARRATIVE`).

`FD_TRANSACTIONS` is the customer-facing ledger; these two are the bank's own
bookkeeping for the same events — a deposit is a liability, interest paid is an expense,
neither visible from the customer ledger alone.

Interest follows accrual accounting: each end-of-day run recognises the day's interest in
the GL (Dr `FD_INT_EXP` / Cr `FD_INT_PAYABLE`, `FDGE_FDT_ID` null — there is no customer
transaction yet), and when interest is capitalized or paid out it moves out of
`FD_INT_PAYABLE` into the deposit or settlement. `FD_INT_PAYABLE` therefore always equals the
rounded `FDA_ACCRUED_INT_AMT` of the active accounts.

---

## 11. `FD_NOTIFICATION_LOG` (FDNT) — customer communication

**PK — `FDNT_ID BIGINT IDENTITY`.** FKs to `FD_ACCOUNTS`, `CUST_ID` (logical), and G2's
`CUSTOMER_COMMUNICATION` (logical, via `FDNT_COMM_ID`).

| Column | Why |
|---|---|
| `FDNT_EVENT_TYP` | ACCOUNT_OPENED / FDR_ISSUED / INTEREST_PAID / MATURITY_NOTICE / etc. |
| `FDNT_CHANNEL` | EMAIL / SMS / ALERT |
| `FDNT_LANGUAGE` | ISO 639-1 code — see §7 |
| `FDNT_TEMPLATE_NAME` / `FDNT_RECIPIENT_SNAP` / `FDNT_PAYLOAD` | Snapshots of what was actually sent — templates and contact details change later |
| `FDNT_STS` | PENDING / SENT / FAILED — **mutable**, updated as delivery progresses |
| `FDNT_SENT_TS` + `FDNT_EFCTV_DT` + audit set | |

**Removed:** `FDNT_RETRY_CNT`. Status stays and stays mutable per your requirement;
retry-attempt counting was cut as unnecessary schema — failures can be handled/logged at
the application level instead.

---

## 12. `FD_BATCH_RUN_LOG` (FDJB) — batch execution audit

**PK — `FDJB_ID BIGINT IDENTITY`.** One row per job run (simplified — no longer one row
per thread/partition).

| Column | Why |
|---|---|
| `FDJB_JOB_NAME` | ACCRUAL or MATURITY |
| `FDJB_BUSINESS_DT` | Business date processed |
| `FDJB_STS` | RUNNING / SUCCESS / FAILED / PARTIAL — aligned with the OpenAPI `BatchRun.status` enum; PARTIAL = the run completed but skipped some accounts |
| `FDJB_READ_CNT` / `WRITE_CNT` / `SKIP_CNT` | Standard batch chunk metrics |
| `FDJB_START_TS` / `FDJB_END_TS` / `FDJB_ERROR_MSG` | |
| `FDJB_EFCTV_DT` + audit set | |

**Removed:** `FDJB_PARTITION_KEY`. Per-thread/per-partition granularity was cut —
multithreading still happens at the application level, this table just logs the overall
job run rather than tracking each thread's slice separately.

---

## 13. `FD_BUSINESS_CLOCK` (FDBC) — unchanged

Kept as originally designed — the module's controllable "current business date," so
batch processing and account "time travel" don't depend on the system clock.

---

## 14. Open items (unresolved, need other groups)

**Rate selection needs a customer→category mapping (G1).** G2 prices by
`category_code`; nothing in G1's schema currently maps a customer to one.

**Premature withdrawal: handoff option A retained pending G2 integration.**
`penalty = min(available balance, round(lifetime interest × penalty-pct / 100 + flat-fee))`.
Defaults: `fd.premature-withdrawal.penalty-pct=1.00` and
`fd.premature-withdrawal.flat-fee=0.00`. Round once, HALF_UP, to currency decimals.
Lifetime interest includes SIMPLE payouts already received, so the penalty may reduce
principal. No minimum holding period is imposed. Interest covers `[value date, withdrawal
date)`; same-day closure earns zero interest and attracts only any configured flat fee.
This preserves the team's current rule; it does not claim a new agreement from Group 2.

The read-only withdrawal quote and locked execution use the existing InterestCalculator
and ledger. `expectedQuote` protects the customer's confirmation against a changed date
or amounts. Closure uses `CLOSED`, preserving existing API/report filters. The optional
reason is stored in `FDT_REMARKS` on WITHDRAWAL (PENALTY if the fee consumes the full balance).
No new table, column, savings account or notification subsystem is introduced.

Worked examples (100,000 principal, 6.5%, ACT/ACT, 1% penalty, no flat fee):

| Terms / dates | Lifetime interest | Final interest | Penalty | Closure payout |
|---|---:|---:|---:|---:|
| COMPOUND quarterly, 1 Jan–16 Jul 2026 | 3,525.06 | 275.80 | 35.25 | 103,489.81 |
| SIMPLE monthly, 1 Jan–1 Feb 2026; 552.05 paid separately | 552.05 | 0.00 | 5.52 | 99,994.48 |
| Same-day, 1 Jan 2026 | 0.00 | 0.00 | 0.00 | 100,000.00 |

These exact cases are asserted in `PrematureWithdrawalQuoteIntegrationTest`.
`sql/reconcile_closure.sql` checks balance continuity, zero closed balances and GL legs.
Because existing SIMPLE payout INTEREST credits leave the deposit balance unchanged,
their separately settled amount is subtracted from raw credits-minus-debits to obtain
the deposit's zero net. The query reports both values instead of treating an external
payout as retained principal. Integration tests also verify detection of a corrupted row.

Group 1's owner/BANK_OFFICER claims and the maturity team's PREMATURE_CLOSURE notification
writer remain unavailable. Hook points are documented in the withdrawal service; no
unverified savings-transfer contract is assumed.

**Day-count convention not published by G2.** `FDA_DAY_COUNT_CONV` defaults to `ACT/ACT`
for new accounts (days falling in a leap year earn 1/366 of the annual rate, other days
1/365) until G2 confirms a product-level field. Accounts booked earlier keep the `ACT/365`
they were snapshotted with (P1).

**Interest type not published by G2.** `FDA_INT_TYP` (SIMPLE / COMPOUND) is resolved at
booking from the create request's `interestType`, then the product's `defaultInterestType`
(a field fd-service's `ProductDetails` projection now expects but G2 has not confirmed),
then the `fd.interest.default-interest-type` setting (COMPOUND). Rows booked before this
was wired hold `NULL` and are read with the same configured default. It matters beyond
display: COMPOUND capitalizes at `FDA_COMPOUND_FREQ` boundaries, SIMPLE pays out at
`FDA_PAYOUT_FREQ` boundaries.

**By-user report can't resolve `USR_ID → CUST_ID` in a stateless `report-service`.**
Recommend the API gateway resolve this from the JWT and pass `CUST_ID` down, rather than
duplicating the mapping into `fd_db`.

---

## 15. Summary of table set

| Table | Purpose |
|---|---|
| `FD_ACCOUNTS` | The deposit contract |
| `FD_TRANSACTIONS` | Customer-facing ledger, including TDS events |
| `FD_ACCOUNT_ROLES` | Owner / joint holder / nominee / guardian / etc. |
| `FD_ACCOUNT_SEQUENCE` | Per-branch account-number counter |
| `FD_GL_ACCOUNTS` + `FD_GL_ENTRIES` | Double-entry internal bookkeeping |
| `FD_NOTIFICATION_LOG` | Customer communication record |
| `FD_BATCH_RUN_LOG` | EOD batch execution audit |
| `FD_BUSINESS_CLOCK` | Controllable business date |

Nine tables, down from the original ten — `FD_TAX_DEDUCTION` folded into
`FD_TRANSACTIONS`.
