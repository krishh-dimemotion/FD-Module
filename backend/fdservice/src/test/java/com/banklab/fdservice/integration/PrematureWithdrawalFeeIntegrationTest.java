package com.banklab.fdservice.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.test.context.TestPropertySource;

import com.banklab.fdservice.entity.FdTransaction;
import com.banklab.fdservice.support.FdIntegrationTestSupport;

@TestPropertySource(properties = "fd.premature-withdrawal.flat-fee=200")
class PrematureWithdrawalFeeIntegrationTest extends FdIntegrationTestSupport {

    @ParameterizedTest
    @CsvSource({"1000,200,800", "100,100,0"})
    void sameDayFeeExceedsInterestAndIsCappedAtBalance(String principal, String penalty, String payout) throws Exception {
        UUID id = createAccount(accountJson(principal, 12, "RATE-12M", "COMPOUND", "QUARTERLY", "ON_MATURITY"));
        String quote = mockMvc.perform(post("/fd-accounts/{id}/withdrawal-quote", id))
                .andExpect(status().isOk()).andExpect(jsonPath("$.interestEarned").value(0))
                .andReturn().getResponse().getContentAsString();
        withdraw(id, "{\"remarks\":\"Fee closure\",\"expectedQuote\":" + quote + "}")
                .andExpect(status().isOk()).andExpect(jsonPath("$.account.status").value("CLOSED"))
                .andExpect(jsonPath("$.penaltyApplied").value(new BigDecimal(penalty).doubleValue()))
                .andExpect(jsonPath("$.withdrawalAmount").value(new BigDecimal(payout).doubleValue()));
        if (new BigDecimal(payout).signum() == 0) {
            assertThat(transactions(id)).extracting(FdTransaction::getTxnTyp).containsExactly("DEPOSIT", "PENALTY");
        } else {
            assertThat(transactions(id)).extracting(FdTransaction::getTxnTyp)
                    .containsExactly("DEPOSIT", "PENALTY", "WITHDRAWAL");
        }
        assertThat(transactions(id).get(1).getAmt()).isEqualByComparingTo(penalty);
        assertThat(transactions(id).getLast().getRemarks()).isEqualTo("Fee closure");
        assertLedgerReconciles();
        assertThat(jdbc.queryForList(Files.readString(Path.of("sql/reconcile_closure.sql"))))
                .allSatisfy(row -> assertThat(row.get("reconciliation")).isEqualTo("balanced"));
    }
}
