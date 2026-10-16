package com.banklab.fdservice.integration;

import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import com.banklab.fdservice.entity.FdTransaction;
import com.banklab.fdservice.service.*;
import com.banklab.fdservice.support.FdIntegrationTestSupport;
import com.banklab.fdservice.tax.*;
import com.jayway.jsonpath.JsonPath;

class DemoTaxIntegrationTest extends FdIntegrationTestSupport {
    @Autowired TaxProperties policy;
    @Autowired InterestPostingService posting;
    @Autowired PrematureWithdrawalService withdrawal;
    @Autowired MaturityRenewalService renewal;
    @BeforeEach void taxOn() { policy.setEnabled(true);policy.setRate(new BigDecimal("10"));policy.getAllowances().clear();policy.getYears().clear();policy.getExemptCustomers().clear(); }
    @AfterEach void taxOff() { policy.setEnabled(false);policy.getAllowances().clear();policy.getYears().clear();policy.getExemptCustomers().clear(); }
    private UUID book(String currency,String principal,String type,String customer) throws Exception {
        return createAccount(accountJson(principal,12,"RATE-12M",type,"QUARTERLY","MONTHLY")
            .replace("\"INR\"","\""+currency+"\"").replace("CUST-001",customer));
    }
    private BigDecimal sum(UUID id,String type) { return transactions(id).stream().filter(t->type.equals(t.getTxnTyp())).map(FdTransaction::getAmt).reduce(BigDecimal.ZERO,BigDecimal::add); }
    @ParameterizedTest @CsvSource({"JPY,123456,0","INR,123456.78,2","USD,123456.78,2","BHD,123456.789,3","KWD,123456.789,3"})
    void exactRoundTripAndPerCurrencyPosting(String currency,String amount,int digits) throws Exception {
        UUID id=book(currency,amount,"COMPOUND","PRECISION");
        assertThat(account(id).getPrincipalAmt()).isEqualByComparingTo(amount);
        mockMvc.perform(get("/fd-accounts/{id}",id)).andExpect(jsonPath("$.principalAmt").isString())
            .andExpect(jsonPath("$.principalAmt").value(amount)).andExpect(jsonPath("$.ccyDecimals").value(digits));
        posting.mature(id,date("2027-01-01"));
        for(var t:transactions(id)) assertThat(t.getAmt().stripTrailingZeros().scale()).isLessThanOrEqualTo(digits);
        assertPerCurrencyLedger();
    }
    @Test void compoundNetProjectionMatchesMaturityAndRerunDoesNothing() throws Exception {
        UUID id=book("INR","100000","COMPOUND","COMPOUND");
        BigDecimal quoted=account(id).getMatAmt();
        posting.mature(id,date("2027-01-01"));
        assertThat(sum(id,"MATURITY_PAYOUT")).isEqualByComparingTo(quoted);
        assertThat(sum(id,"TDS")).isPositive();
        assertThat(sum(id,"MATURITY_PAYOUT")).isEqualByComparingTo(new BigDecimal("100000").add(sum(id,"INTEREST")).subtract(sum(id,"TDS")));
        int count=transactions(id).size();assertThat(posting.mature(id,date("2027-01-01"))).isFalse();
        assertThat(transactions(id)).hasSize(count);assertPerCurrencyLedger();
    }
    @Test void simplePayoutTaxNeverReducesDepositBalance() throws Exception {
        UUID id=book("INR","120000","SIMPLE","SIMPLE");
        posting.accrue(id,date("2026-03-01"));
        assertThat(account(id).getPrincipalBal()).isEqualByComparingTo("120000");
        assertThat(sum(id,"TDS")).isEqualByComparingTo("126.09");
        assertThat(transactions(id).stream().filter(t->"TDS".equals(t.getTxnTyp()))).allSatisfy(t->assertThat(t.getBalAfter()).isEqualByComparingTo(t.getBalBefore()));
        assertPerCurrencyLedger();
    }
    @Test void allowanceSharedAcrossAccountsFreezesOnSettlementAndResetsByYear() throws Exception {
        policy.getAllowances().put("INR",new BigDecimal("1000"));
        UUID a=book("INR","120000","SIMPLE","SHARED"), b=book("INR","120000","SIMPLE","SHARED");
        posting.accrue(a,date("2026-02-01")); // 662.47, below allowance
        assertThat(sum(a,"TDS")).isZero();
        policy.getAllowances().put("INR",BigDecimal.ZERO); // frozen FY retains original allowance
        posting.accrue(b,date("2026-02-01"));
        assertThat(sum(b,"TDS")).isEqualByComparingTo("32.49");
        posting.accrue(a,date("2026-04-01"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM FD_TAX_YEARS WHERE CUSTOMER_ID='SHARED'",Integer.class)).isEqualTo(2);
        assertPerCurrencyLedger();
    }
    @Test void exemptionsAndZeroInterestStillRecordAssessmentsWithoutFabricatingDeductions() throws Exception {
        policy.getExemptCustomers().add("EXEMPT");
        UUID id=book("KWD","120000.123","COMPOUND","EXEMPT");
        posting.accrue(id,date("2026-01-02"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM FD_TAX_YEARS",Integer.class)).isZero();
        posting.mature(id,date("2027-01-01"));
        assertThat(sum(id,"TDS")).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM FD_TAX_EVENTS",Integer.class)).isPositive();
        assertPerCurrencyLedger();
    }
    @Test void dailyAndCatchupProduceIdenticalTaxAndCompoundBalances() throws Exception {
        UUID daily=book("KWD","100000.123","COMPOUND","DAILY"), catchup=book("KWD","100000.123","COMPOUND","CATCHUP");
        for(LocalDate d=date("2026-01-01");d.isBefore(date("2026-08-01"));d=d.plusDays(1)) posting.accrue(daily,d);
        posting.accrue(catchup,date("2026-07-31"));
        assertThat(account(daily).getPrincipalBal()).isEqualByComparingTo(account(catchup).getPrincipalBal());
        assertThat(account(daily).getAccruedIntAmt()).isEqualByComparingTo(account(catchup).getAccruedIntAmt());
        assertThat(sum(daily,"TDS")).isEqualByComparingTo(sum(catchup,"TDS"));assertPerCurrencyLedger();
    }
    @Test void withdrawalQuoteIsReadOnlyAndMatchesNetExecutionAndRenewalsActivateTax() throws Exception {
        UUID id=book("BHD","100000.123","COMPOUND","WITHDRAW");
        businessClock.setBusinessDate(date("2026-07-15"));
        var before=jdbc.queryForList("SELECT * FROM FD_TAX_YEARS");var quote=withdrawal.quote(id);
        assertThat(jdbc.queryForList("SELECT * FROM FD_TAX_YEARS")).isEqualTo(before);
        var result=withdrawal.withdraw(id,null);
        assertThat(result.withdrawalAmount()).isEqualByComparingTo(quote.withdrawalAmount());
        assertThat(result.taxDeducted()).isEqualByComparingTo(quote.taxDeducted());
        policy.setEnabled(false);UUID legacy=book("INR","100000","COMPOUND","LEGACY");
        policy.setEnabled(true);businessClock.setBusinessDate(account(legacy).getMatDt());
        var renewed=renewal.renew(legacy,null);
        assertThat(sum(legacy,"TDS")).isZero();assertThat(renewed.taxEnabled()).isTrue();assertPerCurrencyLedger();
    }
    @Test void concurrentAccountsConsumeAllowanceOnceAndConcurrentRerunsAreIdempotent() throws Exception {
        policy.getAllowances().put("INR",new BigDecimal("1000"));
        UUID a=book("INR","120000","SIMPLE","CONCURRENT"), b=book("INR","120000","SIMPLE","CONCURRENT");
        try(var pool=Executors.newFixedThreadPool(2)) {
            var tasks=List.<Callable<Boolean>>of(()->posting.accrue(a,date("2026-02-01")),()->posting.accrue(b,date("2026-02-01")));
            for(var f:pool.invokeAll(tasks)) assertThat(f.get(20,TimeUnit.SECONDS)).isTrue();
            var repeat=List.<Callable<Boolean>>of(()->posting.accrue(a,date("2026-02-01")),()->posting.accrue(a,date("2026-02-01")));
            for(var f:pool.invokeAll(repeat)) assertThat(f.get(20,TimeUnit.SECONDS)).isFalse();
        }
        assertThat(sum(a,"TDS").add(sum(b,"TDS"))).isEqualByComparingTo("32.49");assertPerCurrencyLedger();
    }
    private void assertPerCurrencyLedger() {
        assertThat(jdbc.queryForObject("""
            SELECT COUNT(*) FROM (SELECT e.FDGE_TXN_GRP_ID,g.FDGL_CCY_CD,
            SUM(CASE WHEN e.FDGE_DR_CR='D' THEN e.FDGE_AMT ELSE -e.FDGE_AMT END) gap
            FROM FD_GL_ENTRIES e JOIN FD_GL_ACCOUNTS g ON g.FDGL_CD=e.FDGE_GL_CD
            GROUP BY e.FDGE_TXN_GRP_ID,g.FDGL_CCY_CD HAVING gap<>0) gaps
            """,Integer.class)).isZero();
        for(var row:jdbc.queryForList("SELECT FDGL_CCY_CD,SUM(FDGL_CURRENT_BAL) tax FROM FD_GL_ACCOUNTS WHERE FDGL_CD LIKE 'FD_TAX_PAYABLE%' GROUP BY FDGL_CCY_CD")) {
            BigDecimal deducted=jdbc.queryForObject("SELECT COALESCE(SUM(FDT_AMT),0) FROM FD_TRANSACTIONS WHERE FDT_TXN_TYP='TDS' AND FDT_CCY_CD=?",BigDecimal.class,row.get("FDGL_CCY_CD"));
            assertThat((BigDecimal)row.get("tax")).isEqualByComparingTo(deducted);
        }
    }
}
