package com.banklab.fdservice.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.banklab.fdservice.entity.FdTransaction;
import com.banklab.fdservice.support.FdIntegrationTestSupport;
import com.jayway.jsonpath.JsonPath;

class PrematureWithdrawalQuoteIntegrationTest extends FdIntegrationTestSupport {

    // Independently worked amounts; also documented in docs/premature-withdrawal.md.
    @ParameterizedTest
    @CsvSource({
            "2026-01-01,2026-01-01,COMPOUND,ON_MATURITY,INR,0,0,0,100000,0",
            "2026-01-01,2026-01-02,COMPOUND,ON_MATURITY,INR,17.81,17.81,0.18,100017.63,17.81",
            "2026-01-01,2026-04-01,COMPOUND,ON_MATURITY,INR,1602.74,0,16.03,101586.71,1602.74",
            "2026-01-01,2026-04-02,COMPOUND,ON_MATURITY,INR,1620.83,18.09,16.21,101604.62,1602.74|18.09",
            "2026-01-01,2026-07-16,COMPOUND,ON_MATURITY,INR,3525.06,275.80,35.25,103489.81,1602.74|1646.52|275.80",
            "2026-01-01,2026-12-31,COMPOUND,ON_MATURITY,INR,6641.47,1700.62,66.41,106575.06,1602.74|1646.52|1691.59|1700.62",
            "2026-01-01,2026-02-01,SIMPLE,MONTHLY,INR,552.05,0,5.52,99994.48,552.05",
            "2026-01-01,2026-02-02,SIMPLE,MONTHLY,INR,569.86,17.81,5.70,100012.11,552.05|17.81",
            "2028-02-28,2028-03-01,COMPOUND,ON_MATURITY,INR,35.52,35.52,0.36,100035.16,35.52",
            "2026-01-01,2026-01-02,COMPOUND,ON_MATURITY,JPY,18,18,0,100018,18"
    })
    void quoteIsReadOnlyAndMatchesClosureAtTimingBoundaries(String start, String end, String type, String frequency,
            String currency, String earned, String finalInterest, String penalty, String payout, String interestRows)
            throws Exception {
        businessClock.setBusinessDate(date(start));
        UUID id = createAccount(accountJson("100000", 12, "RATE-12M", type, "QUARTERLY", frequency)
                .replace("INR", currency));
        businessClock.setBusinessDate(date(end));
        List<?> before = snapshot();
        String quote = quote(id).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(snapshot()).isEqualTo(before);
        assertAmount(quote, "interestEarned", earned);
        assertAmount(quote, "finalInterest", finalInterest);
        assertAmount(quote, "penaltyApplied", penalty);
        assertAmount(quote, "withdrawalAmount", payout);
        String result = withdraw(id, "{\"remarks\":\"Customer request\",\"expectedQuote\":" + quote + "}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.account.status").value("CLOSED"))
                .andReturn().getResponse().getContentAsString();
        assertSameQuote(quote, result);
        assertThat(account(id).getClosureDt()).isEqualTo(date(end));

        List<String> types = new ArrayList<>(List.of("DEPOSIT"));
        List<BigDecimal> amounts = new ArrayList<>(List.of(new BigDecimal("100000")));
        for (String amount : interestRows.split("\\|")) {
            if (new BigDecimal(amount).signum() > 0) {
                types.add("INTEREST");
                amounts.add(new BigDecimal(amount));
            }
        }
        if (new BigDecimal(penalty).signum() > 0) {
            types.add("PENALTY");
            amounts.add(new BigDecimal(penalty));
        }
        types.add("WITHDRAWAL");
        amounts.add(new BigDecimal(payout));
        assertThat(transactions(id)).extracting(FdTransaction::getTxnTyp).containsExactlyElementsOf(types);
        assertThat(transactions(id)).extracting(FdTransaction::getAmt)
                .usingElementComparator(BigDecimal::compareTo).containsExactlyElementsOf(amounts);
        assertThat(transactions(id).getLast().getRemarks()).isEqualTo("Customer request");
        assertReferences(id, result);
        assertLedgerReconciles();
        assertSqlBalanced();
    }

    @Test
    void fullLifecycleWithEodQuoteWithdrawalAndBatchesSkippingClosure() throws Exception {
        UUID id = createDefaultAccount();
        // Run actual EOD, then deliberately miss subsequent days before closure.
        for (int day = 0; day < 3; day++) {
            mockMvc.perform(post("/business-clock/eod")).andExpect(status().isOk());
        }
        businessClock.setBusinessDate(date("2026-07-16"));
        String quote = quote(id).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertAmount(quote, "withdrawalAmount", "103489.81");
        String result = withdraw(id, "{\"expectedQuote\":" + quote + "}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertSameQuote(quote, result);
        assertReferences(id, result);
        assertLedgerReconciles();
        assertSqlBalanced();
        List<?> closedState = snapshot();
        withdraw(id, "{}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_ACTIVE"));
        assertThat(snapshot()).isEqualTo(closedState);
        runBatch("ACCRUAL", "2026-08-01").andExpect(jsonPath("$.readCnt").value(0));
        runBatch("MATURITY", "2027-01-01").andExpect(jsonPath("$.readCnt").value(0));
        assertThat(transactions(id)).hasSize(6);
        assertThat(account(id).getSts()).isEqualTo("CLOSED");
    }

    @Test
    void quoteIncludesExistingUnsettledAccrualWhenPlannerIsNoOp() throws Exception {
        UUID id = createDefaultAccount();
        runBatch("ACCRUAL", "2026-01-01");
        businessClock.setBusinessDate(date("2026-01-02"));
        String quote = quote(id).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertAmount(quote, "finalInterest", "17.81");
        String result = withdraw(id, "{\"expectedQuote\":" + quote + "}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertSameQuote(quote, result);
        assertLedgerReconciles();
    }

    @Test
    void quoteIncludesInterestAlreadyPaidOutByBatch() throws Exception {
        UUID id = createAccount(accountJson("100000", 12, "RATE-12M", "SIMPLE", "QUARTERLY", "MONTHLY"));
        runBatch("ACCRUAL", "2026-01-31");
        businessClock.setBusinessDate(date("2026-02-01"));
        String quote = quote(id).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertAmount(quote, "interestEarned", "552.05");
        assertAmount(quote, "withdrawalAmount", "99994.48");
        withdraw(id, "{\"expectedQuote\":" + quote + "}").andExpect(status().isOk());
        assertLedgerReconciles();
        assertSqlBalanced();
    }

    @Test
    void changedQuoteIsRejectedBeforeAnyWrite() throws Exception {
        UUID id = createDefaultAccount();
        String quote = quote(id).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        businessClock.setBusinessDate(date("2026-01-02"));
        List<?> before = snapshot();
        withdraw(id, "{\"expectedQuote\":" + quote + "}").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("WITHDRAWAL_QUOTE_CHANGED"));
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void invalidBodiesAreRejectedBeforeAnyWrite() throws Exception {
        UUID id = createDefaultAccount();
        List<?> before = snapshot();
        for (String body : List.of("{", "{\"amount\":-1}", "{\"expectedQuote\":{}}",
                "{\"remarks\":\"" + "x".repeat(256) + "\"}")) {
            withdraw(id, body).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        }
        assertThat(snapshot()).isEqualTo(before);
    }

    @Test
    void quoteAndWithdrawHaveIdenticalEligibilityErrors() throws Exception {
        UUID id = createDefaultAccount();
        businessClock.setBusinessDate(date("2025-12-31"));
        assertEligibility(id, "WITHDRAWAL_BEFORE_VALUE_DATE");
        businessClock.setBusinessDate(date("2027-01-01"));
        assertEligibility(id, "ACCOUNT_MATURED");
        runBatch("ACCRUAL", "2026-01-02");
        assertEligibility(id, "ACCRUAL_AHEAD_OF_BUSINESS_DATE");
        businessClock.setBusinessDate(date("2026-01-03"));
        withdraw(id, "{}").andExpect(status().isOk());
        assertEligibility(id, "ACCOUNT_NOT_ACTIVE");
        quote(UUID.randomUUID()).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(post("/fd-accounts/not-a-uuid/withdrawal-quote")).andExpect(status().isBadRequest());
    }

    @Test
    void legacyAccountQuoteStillMatchesPrincipalOnlyClosure() throws Exception {
        UUID id = createDefaultAccount();
        removeContractedRate(id);
        businessClock.setBusinessDate(date("2026-07-16"));
        String quote = quote(id).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertAmount(quote, "withdrawalAmount", "100000");
        String result = withdraw(id, "{\"expectedQuote\":" + quote + "}")
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertSameQuote(quote, result);
        assertLedgerReconciles();
    }

    @Test
    void postingFailureRollsBackAccountAccrualAllTransactionsAndGlBalances() throws Exception {
        UUID id = createDefaultAccount();
        businessClock.setBusinessDate(date("2026-07-16"));
        jdbc.update("UPDATE FD_GL_ACCOUNTS SET FDGL_IS_ACTIVE = false WHERE FDGL_CD = 'FD_PENALTY_INC'");
        try {
            List<?> before = snapshot();
            withdraw(id, "{}").andExpect(status().isInternalServerError())
                    .andExpect(jsonPath("$.code").value("GL_ACCOUNT_NOT_CONFIGURED"));
            assertThat(snapshot()).isEqualTo(before);
            assertThat(account(id).getSts()).isEqualTo("ACTIVE");
            assertLedgerReconciles();
        } finally {
            jdbc.update("UPDATE FD_GL_ACCOUNTS SET FDGL_IS_ACTIVE = true WHERE FDGL_CD = 'FD_PENALTY_INC'");
        }
    }

    @Test
    void reconciliationFlagsACorruptedBalanceSnapshot() throws Exception {
        UUID id = createDefaultAccount();
        withdraw(id, "{}").andExpect(status().isOk());
        assertSqlBalanced();
        jdbc.update("UPDATE FD_TRANSACTIONS SET FDT_BAL_BEFORE = FDT_BAL_BEFORE + 1 WHERE FDA_ID = ? AND FDT_TXN_TYP = 'WITHDRAWAL'",
                id.toString());
        assertThat(reconciliationRows().getFirst().get("reconciliation")).isEqualTo("UNBALANCED");
    }

    private UUID createDefaultAccount() throws Exception {
        return createAccount(accountJson("100000", 12, "RATE-12M", "COMPOUND", "QUARTERLY", "ON_MATURITY"));
    }

    private ResultActions quote(UUID id) throws Exception {
        return mockMvc.perform(post("/fd-accounts/{id}/withdrawal-quote", id));
    }

    private void assertEligibility(UUID id, String code) throws Exception {
        List<?> before = snapshot();
        quote(id).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(code));
        withdraw(id, "{}").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value(code));
        assertThat(snapshot()).isEqualTo(before);
    }

    private List<?> snapshot() {
        return List.of(jdbc.queryForList("SELECT * FROM FD_ACCOUNTS ORDER BY FDA_ID"),
                jdbc.queryForList("SELECT * FROM FD_TRANSACTIONS ORDER BY FDT_ID"),
                jdbc.queryForList("SELECT * FROM FD_GL_ENTRIES ORDER BY FDGE_ID"),
                jdbc.queryForList("SELECT * FROM FD_GL_ACCOUNTS ORDER BY FDGL_CD"),
                jdbc.queryForList("SELECT * FROM FD_BUSINESS_CLOCK ORDER BY FDBC_ENTITY_CD"));
    }

    private void assertSqlBalanced() throws Exception {
        assertThat(reconciliationRows()).isNotEmpty()
                .allSatisfy(row -> assertThat(row.get("reconciliation")).isEqualTo("balanced"));
    }

    private List<Map<String, Object>> reconciliationRows() throws Exception {
        return jdbc.queryForList(Files.readString(Path.of("sql/reconcile_closure.sql")));
    }

    private void assertReferences(UUID id, String result) {
        String group = JsonPath.read(result, "$.txnGrpId");
        List<Number> ids = JsonPath.read(result, "$.transactionIds");
        assertThat(ids.stream().map(Number::longValue).toList()).containsExactlyElementsOf(transactions(id).stream()
                .filter(txn -> txn.getTxnGrpId().toString().equals(group)).map(FdTransaction::getFdtId).toList());
    }

    private static void assertAmount(String body, String field, String expected) {
        assertThat(new BigDecimal(JsonPath.read(body, "$." + field).toString())).isEqualByComparingTo(expected);
    }

    private static void assertSameQuote(String quote, String result) {
        for (String field : List.of("withdrawalDate", "interestEarned", "finalInterest", "penaltyApplied", "withdrawalAmount")) {
            assertThat((Object) JsonPath.read(result, "$." + field)).isEqualTo((Object) JsonPath.read(quote, "$." + field));
        }
    }
}
