-- MySQL 8, read-only. Run in fd_db (or fd_db_test for tests).
-- One result per closed account; every result should say balanced.
-- Change FD_SETTLEMENT below if fd.gl.settlement is configured differently.
-- SIMPLE payout INTEREST rows are credits paid directly to settlement, not
-- retained deposits. raw_ledger_net - interest_paid_out must therefore be zero.
WITH transaction_legs AS (
    SELECT t.fdt_id,
           COUNT(g.fdge_id) AS leg_count,
           SUM(CASE WHEN g.fdge_dr_cr = 'D' THEN 1 ELSE 0 END) AS debit_count,
           SUM(CASE WHEN g.fdge_dr_cr = 'C' THEN 1 ELSE 0 END) AS credit_count,
           SUM(CASE WHEN g.fdge_amt = t.fdt_amt
                     AND g.fdge_txn_grp_id = t.fdt_txn_grp_id THEN 0 ELSE 1 END) AS bad_legs,
           SUM(CASE WHEN t.fdt_txn_typ = 'INTEREST' AND g.fdge_dr_cr = 'C'
                     AND g.fdge_gl_cd = 'FD_SETTLEMENT' THEN g.fdge_amt ELSE 0 END) AS paid_out
    FROM fd_transactions t
    LEFT JOIN fd_gl_entries g ON g.fdge_fdt_id = t.fdt_id
    GROUP BY t.fdt_id
), ordered_transactions AS (
    SELECT t.*, l.leg_count, l.debit_count, l.credit_count, l.bad_legs, l.paid_out,
           LAG(t.fdt_bal_after, 1, 0) OVER (PARTITION BY t.fda_id ORDER BY t.fdt_id) AS previous_balance,
           ROW_NUMBER() OVER (PARTITION BY t.fda_id ORDER BY t.fdt_id DESC) AS reverse_order
    FROM fd_transactions t
    JOIN transaction_legs l ON l.fdt_id = t.fdt_id
), account_checks AS (
    SELECT a.fda_id, a.fda_acct_num, a.fda_principal_bal, a.fda_accrued_int_amt,
           COUNT(t.fdt_id) AS transaction_count,
           COALESCE(SUM(CASE WHEN t.fdt_dr_cr = 'C' THEN t.fdt_amt ELSE -t.fdt_amt END), 0) AS raw_ledger_net,
           COALESCE(SUM(t.paid_out), 0) AS interest_paid_out,
           SUM(CASE WHEN t.fdt_bal_before = t.previous_balance THEN 0 ELSE 1 END) AS broken_links,
           SUM(CASE WHEN t.fdt_amt > 0 AND t.fdt_dr_cr IN ('C', 'D')
                     AND t.fdt_bal_after - t.fdt_bal_before =
                         (CASE WHEN t.fdt_dr_cr = 'C' THEN t.fdt_amt ELSE -t.fdt_amt END) - t.paid_out
                    THEN 0 ELSE 1 END) AS bad_movements,
           SUM(CASE WHEN t.leg_count = 2 AND t.debit_count = 1 AND t.credit_count = 1
                     AND t.bad_legs = 0 THEN 0 ELSE 1 END) AS bad_postings,
           MAX(CASE WHEN t.reverse_order = 1 THEN t.fdt_bal_after END) AS final_ledger_balance
    FROM fd_accounts a
    LEFT JOIN ordered_transactions t ON t.fda_id = a.fda_id
    WHERE a.fda_sts IN ('CLOSED', 'MATURED_CLOSED')
    GROUP BY a.fda_id, a.fda_acct_num, a.fda_principal_bal, a.fda_accrued_int_amt
)
SELECT *, raw_ledger_net - interest_paid_out AS deposit_ledger_net,
       CASE WHEN transaction_count > 0 AND fda_principal_bal = 0 AND fda_accrued_int_amt = 0
                 AND final_ledger_balance = 0 AND raw_ledger_net - interest_paid_out = 0
                 AND broken_links = 0 AND bad_movements = 0 AND bad_postings = 0
            THEN 'balanced' ELSE 'UNBALANCED' END AS reconciliation
FROM account_checks
ORDER BY fda_acct_num;
