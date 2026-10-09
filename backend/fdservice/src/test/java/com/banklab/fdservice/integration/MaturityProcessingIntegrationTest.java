package com.banklab.fdservice.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.banklab.fdservice.entity.FdAccount;
import com.banklab.fdservice.entity.FdTransaction;
import com.banklab.fdservice.support.FdIntegrationTestSupport;
import com.jayway.jsonpath.JsonPath;

/** Maturity cases that are not covered by the normal EOD lifecycle test. */
class MaturityProcessingIntegrationTest extends FdIntegrationTestSupport {

    @Test
    void missedMaturityIsClosedOnceWhenTheBatchRunsAfterTheMaturityDate() throws Exception {
        UUID fdaId = createAccount(accountJson("100000.00", 1, "RATE-1M", "COMPOUND", "MONTHLY", "ON_MATURITY"));

        runBatch("MATURITY", "2026-02-05")
                .andExpect(jsonPath("$.status").value("SUCCESS"))
                .andExpect(jsonPath("$.readCnt").value(1));

        FdAccount matured = account(fdaId);
        assertThat(matured.getSts()).isEqualTo("MATURED_CLOSED");
        assertThat(matured.getClosureDt()).isEqualTo(date("2026-02-05"));
        assertThat(matured.getPrincipalBal()).isEqualByComparingTo("0.00");
        assertThat(transactions(fdaId)).extracting(FdTransaction::getTxnTyp)
                .containsExactly("DEPOSIT", "INTEREST", "MATURITY_PAYOUT");
        assertThat(transactions(fdaId).get(2).getAmt()).isEqualByComparingTo("100552.05");

        runBatch("MATURITY", "2026-02-05").andExpect(jsonPath("$.readCnt").value(0));
        assertThat(transactions(fdaId)).hasSize(3);
        assertLedgerReconciles();
    }

    @Test
    void maturedAccountCanBeRenewedIntoANewLinkedAccountBeforeTheBatchRuns() throws Exception {
        UUID oldId = createAccount(accountJson("100000.00", 12, "RATE-12M", "COMPOUND", "QUARTERLY", "ON_MATURITY"));
        businessClock.setBusinessDate(date("2027-01-01"));

        String body = mockMvc.perform(post("/fd-accounts/{id}/renew", oldId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"tenureMonths\":6}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.renewedFromId").value(oldId.toString()))
                .andExpect(jsonPath("$.openDt").value("2027-01-01"))
                .andExpect(jsonPath("$.matDt").value("2027-07-01"))
                .andExpect(jsonPath("$.principalAmt").value(106660.15))
                .andReturn().getResponse().getContentAsString();
        UUID renewedId = UUID.fromString(JsonPath.read(body, "$.fdaId"));

        FdAccount oldAccount = account(oldId);
        FdAccount renewed = account(renewedId);
        assertThat(oldAccount.getSts()).isEqualTo("MATURED_RENEWED");
        assertThat(oldAccount.getClosureDt()).isEqualTo(date("2027-01-01"));
        assertThat(oldAccount.getPrincipalBal()).isEqualByComparingTo("0.00");
        assertThat(renewed.getPrincipalBal()).isEqualByComparingTo("106660.15");
        assertThat(renewed.getRenewedFromId()).isEqualTo(oldId);
        assertThat(renewed.getAcctNum()).isNotEqualTo(oldAccount.getAcctNum());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM FD_ACCOUNT_ROLES WHERE FDA_ID = ?", Integer.class,
                renewedId.toString())).isEqualTo(1);

        List<FdTransaction> oldLedger = transactions(oldId);
        List<FdTransaction> renewedLedger = transactions(renewedId);
        assertThat(oldLedger).extracting(FdTransaction::getTxnTyp)
                .containsExactly("DEPOSIT", "INTEREST", "INTEREST", "INTEREST", "INTEREST", "RENEWAL_TRANSFER");
        assertThat(renewedLedger).singleElement().satisfies(transaction -> {
            assertThat(transaction.getTxnTyp()).isEqualTo("RENEWAL_TRANSFER");
            assertThat(transaction.getAmt()).isEqualByComparingTo("106660.15");
            assertThat(transaction.getBalBefore()).isEqualByComparingTo(BigDecimal.ZERO);
            assertThat(transaction.getBalAfter()).isEqualByComparingTo("106660.15");
            assertThat(transaction.getTxnGrpId()).isEqualTo(oldLedger.get(5).getTxnGrpId());
        });
        assertLedgerReconciles();

        runBatch("MATURITY", "2027-01-01").andExpect(jsonPath("$.readCnt").value(0));
        assertThat(transactions(oldId)).hasSize(6);
    }

    @Test
    void renewalBeforeMaturityOrAfterClosureIsRejectedWithoutCreatingAnotherAccount() throws Exception {
        UUID fdaId = createAccount(accountJson("100000.00", 12, "RATE-12M", "COMPOUND", "QUARTERLY", "ON_MATURITY"));

        mockMvc.perform(post("/fd-accounts/{id}/renew", fdaId).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_MATURED"));
        assertThat(accountRepository.count()).isEqualTo(1);

        runBatch("MATURITY", "2027-01-01").andExpect(jsonPath("$.readCnt").value(1));
        mockMvc.perform(post("/fd-accounts/{id}/renew", fdaId).contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ACCOUNT_NOT_ACTIVE"));
        assertThat(accountRepository.count()).isEqualTo(1);
    }
}
