package com.banklab.fdservice.support;

import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Wipes FD test data in FK order. Test classes against the shared fd_db_test schema
 * call it in @BeforeEach (and @AfterEach where they leave ledger rows behind), so the
 * order test classes run in never matters. GL account rows and the business clock
 * row are reference data — balances are reset, rows are kept.
 */
public final class FdTestDatabase {

    private final JdbcTemplate jdbc;

    public FdTestDatabase(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public void clearFdData() {
        jdbc.update("DELETE FROM FD_TAX_EVENTS");
        jdbc.update("DELETE FROM FD_TAX_YEARS");
        jdbc.update("DELETE FROM FD_GL_ENTRIES");
        jdbc.update("UPDATE FD_TRANSACTIONS SET FDT_REVERSAL_OF_ID = NULL");
        jdbc.update("DELETE FROM FD_TRANSACTIONS");
        jdbc.update("DELETE FROM FD_ACCOUNT_ROLES");
        jdbc.update("DELETE FROM FD_ACCOUNTS");
        jdbc.update("DELETE FROM FD_BATCH_RUN_LOG");
        jdbc.update("UPDATE FD_GL_ACCOUNTS SET FDGL_CURRENT_BAL = 0");
    }
}
