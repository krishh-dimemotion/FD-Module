# Premature withdrawal implementation and verification

Scope: the eight withdrawal tasks in `navya-handoff.pdf`, sections 5.1–5.8.
Account opening, interest calculation, batch maturity, customer/product clients,
the business clock and the transaction/GL writer already existed and are reused.
Renewal, reports, tax deduction, notifications and authentication belong to the
other team blocks described in the handoff.

## Contract and assumptions

- Retain option A: `min(balance, round(lifetime interest * penalty-pct / 100 + flat-fee))`.
  Defaults are 1% and zero, configurable using `fd.premature-withdrawal.*`.
- Include SIMPLE interest already paid out in the penalty base, without paying it
  a second time at closure. The penalty can reduce principal. No minimum holding period.
- Round the combined fee once, HALF_UP, to the account's currency decimals.
- Only ACTIVE deposits on/after their value date and strictly before maturity
  may close. Interest already processed on/after the withdrawal date is rejected.
- Close as `CLOSED`, consistent with the existing API; no new status or schema.
- Use existing settlement GL postings. There is no savings-account service/transfer
  contract here. Owner/BANK_OFFICER checks await G1 JWT claims; notifications await
  the maturity team's writer. These are explicit integration dependencies, not
  simulated implementations. G2 has not confirmed a replacement penalty contract.

## APIs and atomic flow

`POST /fd-accounts/{id}/withdrawal-quote` needs no body. It returns
`withdrawalDate`, `interestEarned`, `finalInterest`, `penaltyApplied` and
`withdrawalAmount`. It projects missing accrual using `InterestCalculator.planAccrual`
without calling the ledger or modifying entities. Account and transaction reads
share a repeatable-read snapshot.

`POST /fd-accounts/{id}/withdraw` remains compatible with the optional `amount`
and `remarks` body. `amount`, if provided, means the currently stored principal
balance before catch-up; it is not the final net payout. The customer page omits
it and supplies the returned quote in `expectedQuote`. A changed date or amount
causes HTTP 409 `WITHDRAWAL_QUOTE_CHANGED` before writes.

Execution locks the existing account row, validates eligibility and the quote,
catches up interest, settles final interest, posts the penalty and net withdrawal,
sets zero balances and closure date, and stamps audit fields in one transaction.
All monetary writes use `FdLedgerService`. Final interest, penalty and withdrawal
share the returned `txnGrpId`; their IDs are returned in `transactionIds`.
Catch-up boundary settlements retain their existing separate groups.

`remarks` accepts up to 255 characters and is saved on the WITHDRAWAL row. If a
fee consumes the whole balance, no zero-value withdrawal is posted and the reason
is saved on PENALTY instead. The original account and transaction entities,
foreign keys and Hibernate schema-generation mechanism are unchanged.

HTTP errors use the existing `{code, message}` handler: 400 validation, 404 missing
account, 409 eligibility/partial/stale-quote conflicts, 422 invalid interest terms,
500 missing/inactive GL configuration. The customer page maps these to messages,
requires acknowledgement, prevents duplicate submission and renders server text
with `textContent`. A lost confirmation response requires a fresh lookup.

## Worked examples used in integration tests

Principal 100,000; annual rate 6.5%; ACT/ACT; 1% penalty; no flat fee.
Quarterly compounding unless marked SIMPLE or JPY.

| Value date | Withdrawal date | Case | Lifetime interest | Final interest | Penalty | Payout |
|---|---|---|---:|---:|---:|---:|
| 2026-01-01 | 2026-01-01 | Same day | 0.00 | 0.00 | 0.00 | 100,000.00 |
| 2026-01-01 | 2026-01-02 | One day | 17.81 | 17.81 | 0.18 | 100,017.63 |
| 2026-01-01 | 2026-04-01 | Boundary | 1,602.74 | 0.00 | 16.03 | 101,586.71 |
| 2026-01-01 | 2026-04-02 | Day after boundary | 1,620.83 | 18.09 | 16.21 | 101,604.62 |
| 2026-01-01 | 2026-07-16 | Handoff example / missed EODs | 3,525.06 | 275.80 | 35.25 | 103,489.81 |
| 2026-01-01 | 2026-12-31 | Day before maturity | 6,641.47 | 1,700.62 | 66.41 | 106,575.06 |
| 2026-01-01 | 2026-02-01 | SIMPLE monthly; 552.05 already paid | 552.05 | 0.00 | 5.52 | 99,994.48 |
| 2026-01-01 | 2026-02-02 | SIMPLE monthly | 569.86 | 17.81 | 5.70 | 100,012.11 |
| 2028-02-28 | 2028-03-01 | Leap day | 35.52 | 35.52 | 0.36 | 100,035.16 |
| 2026-01-01 | 2026-01-02 | JPY | 18 | 18 | 0 | 100,018 |

With a configured flat fee of 200, same-day principal 1,000 pays 800; principal
100 pays zero with the penalty capped at 100. These are tested separately.

## Running backend checks

The repository expects local MySQL at `localhost:3306`, application database
`fd_db`, test database `fd_db_test`, and username `root` unless overridden.
Set credentials in the git-ignored `backend/fdservice/config/application.properties`.
Never use shared/application data for tests: the test profile recreates tables.

From `backend/fdservice`, run `mvn clean verify`. On Windows installations where
Java cannot trust the Maven repository certificate, use Windows' trusted roots:
`mvn -Djavax.net.ssl.trustStoreType=Windows-ROOT -Djavax.net.ssl.trustStore=NONE clean verify`.
Do not disable certificate verification.

`PrematureWithdrawalQuoteIntegrationTest` checks unchanged database snapshots after
quotes/errors, all timing cases, existing batch accrual/payouts, legacy no-rate
accounts, saved reasons, transaction references, rejection of repeat closure,
rollback after catch-up/interest writes, and batches ignoring closed accounts.
`PrematureWithdrawalFeeIntegrationTest` covers flat-fee and zero-payout closures.
Policy tests cover combined-fee rounding and invalid settings.

Run `sql/reconcile_closure.sql` in MySQL 8 against the intended schema. Every
closed account should be `balanced`. It checks zero balances/accrual, transaction
continuity, row amounts, debit/credit legs and the deposit ledger net. Existing
SIMPLE payout credits are already settled externally: `raw_ledger_net` less
`interest_paid_out` must be zero. Integration tests execute this same file and
deliberately corrupt a test row to verify `UNBALANCED` detection. Change the
settlement code in the query if the application's GL code configuration changes.

## Manual browser test

Use an isolated test instance with both upstream stubs enabled. Start the backend
on 8080 and `node server.js` from `frontend` on 3000. These steps move the business
clock; do not run them against a shared team instance.

1. Set the business date to `2026-01-01` in Operations.
2. Open an FD: customer `CUST-001`, product `FD-REG`, principal `100000`, tenure
   `12`, rate `RATE-12M`, currency `INR`, COMPOUND, QUARTERLY, ON_MATURITY and an
   OWNER role. Copy the generated account number.
3. Set the business date to `2026-07-16`. Open **Withdraw FD** from navigation.
4. Look up an unknown account: expect a friendly not-found message. Look up the
   new account: expect ACTIVE, principal/balance 100,000, rate 6.5%, value date
   2026-01-01, maturity 2027-01-01 and business date 2026-07-16.
5. Enter `Customer request` and select **Review withdrawal**. Expect interest
   3,525.06 INR, final interest 275.80, penalty 35.25 and payout 103,489.81.
   Confirm is disabled until the acknowledgement is selected. The account still
   has only its initial DEPOSIT transaction. Cancel must leave it unchanged.
6. Review again. Change the business date in Operations to July 17, then confirm:
   expect a changed-quote message, ACTIVE status and no new transactions.
7. Restore July 16, review again, acknowledge and confirm. Rapid repeated submits
   must produce one closure. Expect CLOSED, the original quoted amounts, the
   saved reason, a transaction group and transaction IDs on the receipt.
8. Inspect transactions: DEPOSIT 100,000; INTEREST 1,602.74, 1,646.52 and 275.80;
   PENALTY 35.25; WITHDRAWAL 103,489.81. Final balance and accrued interest are zero.
   Run the reconciliation SQL. Reloading the closed account disables withdrawal.
9. Run later accrual and maturity jobs: this account must not be read or changed.
10. Check the receipt at a 390px viewport: no horizontal overflow or clipped values.

Screenshots from the automated run are saved as `screenshots/withdrawal-quote.png`,
`screenshots/withdrawal-receipt.png` and `screenshots/withdrawal-receipt-mobile.png`.
The screenshots contain synthetic test data.

Optional automation: `frontend/tests/withdrawal.e2e.cjs` uses Playwright if it is
already available (no runtime dependency added). Set `FD_E2E_ISOLATED_DB=1`, and
optionally `FD_PLAYWRIGHT_MODULE` to its installed module path, then run
`node tests/withdrawal.e2e.cjs` from `frontend`. It runs steps 3–8 and the mobile
check; backend integration tests cover ledger reconciliation and batch exclusion.
Set `FD_BROWSER_CHANNEL=msedge` to use installed Edge if Playwright's bundled
Chromium is unavailable.

## File inventory

Created (paths relative to the repository):

- `backend/fdservice/src/main/java/com/banklab/fdservice/dto/WithdrawalQuoteResponse.java`
- `backend/fdservice/src/test/java/com/banklab/fdservice/integration/PrematureWithdrawalQuoteIntegrationTest.java`
- `backend/fdservice/src/test/java/com/banklab/fdservice/integration/PrematureWithdrawalFeeIntegrationTest.java`
- `backend/fdservice/sql/reconcile_closure.sql`
- `backend/fdservice/docs/premature-withdrawal.md`
- `backend/fdservice/docs/screenshots/withdrawal-quote.png`
- `backend/fdservice/docs/screenshots/withdrawal-receipt.png`
- `backend/fdservice/docs/screenshots/withdrawal-receipt-mobile.png`
- `frontend/withdraw.html`
- `frontend/js/withdraw.js`
- `frontend/tests/withdrawal.e2e.cjs`

Modified existing files:

- `backend/fdservice/src/main/java/com/banklab/fdservice/controller/FdAccountController.java`
- `backend/fdservice/src/main/java/com/banklab/fdservice/dto/PrematureWithdrawalRequest.java`
- `backend/fdservice/src/main/java/com/banklab/fdservice/dto/PrematureWithdrawalResponse.java`
- `backend/fdservice/src/main/java/com/banklab/fdservice/service/AccountNotWithdrawableException.java`
- `backend/fdservice/src/main/java/com/banklab/fdservice/service/PrematureWithdrawalPolicy.java`
- `backend/fdservice/src/main/java/com/banklab/fdservice/service/PrematureWithdrawalService.java`
- `backend/fdservice/src/main/java/com/banklab/fdservice/service/FdLedgerService.java`
- `backend/fdservice/src/main/resources/application.properties` (penalty comments only)
- `backend/fdservice/src/test/java/com/banklab/fdservice/service/PrematureWithdrawalPolicyTest.java`
- `backend/fdservice/src/test/java/com/banklab/fdservice/support/FdIntegrationTestSupport.java`
- `backend/fdservice/fd_module_openapi.yaml`
- `backend/fdservice/fd_module_erd_justification (1) (1).md`
- `frontend/index.html` (navigation)
- `frontend/operations.html` (navigation)

Database changes: none. No entities, columns, tables, foreign keys, database
credentials, dependencies or migration configuration were changed.

## Verification results (5 October 2026)

- Full `mvn verify`: **122 tests, 0 failures, 0 errors, 0 skipped**, packaged
  `target/fdservice-0.0.1-SNAPSHOT.jar`. This includes all 98 existing tests and
  24 added test cases. Ran against installed MySQL **8.0.44**, using a temporary
  loopback-only instance at port 33306 with schema `fd_db_test`. Datasource values
  were overridden for that command only; the repository's local port-3306
  configuration and existing database were not changed. Log: `target/verification-mysql.log`.
- The original default-credential run could not authenticate to the normal local
  MySQL instance. The temporary instance allowed complete MySQL validation without
  accessing or exposing the user's credentials. Configure the ignored local file
  before running the application against the normal `fd_db`.
- Additional isolated H2 run: 121 tests passed; only the explicit MySQL version/schema
  assertion was excluded from that supplementary run. MySQL validation above had
  no exclusions. Focused withdrawal run: 38 tests passed.
- Playwright using installed headless Edge: actual HTTP backend + frontend flow
  passed (lookup, quote without posting, cancel, stale quote, single submission,
  saved reason, receipt, closed-account guard and 390px mobile viewport).
  The browser backend used a temporary H2 database; the same business logic was
  separately verified by the complete MySQL suite. Desktop/mobile screenshots were
  inspected for clipping and overflow.
- JavaScript syntax checks, OpenAPI YAML parsing and `git diff --check` passed.
- Temporary verification services were stopped after the run. No commits, pushes,
  branch changes, new libraries or unrelated module refactors were performed.

Remaining integration dependencies: real Group 1 authentication/customer service,
Group 2 penalty/pricing agreement and the maturity team's closure notification
writer. No known build/test failures remain in the implemented withdrawal scope.
