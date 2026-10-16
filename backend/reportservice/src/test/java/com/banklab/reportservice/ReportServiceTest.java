package com.banklab.reportservice;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.banklab.reportservice.ReportStore.Account;
import com.banklab.reportservice.ReportStore.Txn;

class ReportServiceTest {
    final ReportStore store=mock(ReportStore.class);
    final ReportService reports=new ReportService(store);
    static final String OLD="00000000-0000-0000-0000-000000000001", NEW="00000000-0000-0000-0000-000000000002", USD="00000000-0000-0000-0000-000000000003";
    List<Account> accounts;
    List<Txn> txns;
    static LocalDate d(String s) { return LocalDate.parse(s); }
    static BigDecimal b(String s) { return new BigDecimal(s); }
    static Account account(String id,String ccy,String status,String opened,String closed,String balance,String accrued) {
        return new Account(id,id,"C1","Asha","FD-REG","Regular",ccy,2,status,d(opened),d("2027-01-01"),closed==null?null:d(closed),b("100"),b(balance),b(accrued),b("110"));
    }
    static Txn txn(long id,String account,String type,String dr,String amount,String before,String after,String date) {
        return new Txn(id,account,type,dr,b(amount),before==null?null:b(before),after==null?null:b(after),d(date),null,null,"test");
    }
    @BeforeEach void setup() {
        accounts=List.of(account(OLD,"INR","MATURED_RENEWED","2026-01-01","2026-02-01","0","0"),
            account(NEW,"INR","ACTIVE","2026-02-01",null,"105","1.235"),account(USD,"USD","ACTIVE","2026-01-15",null,"50","0"));
        txns=List.of(txn(1,OLD,"DEPOSIT","C","100","0","100","2026-01-01"),
            txn(2,USD,"DEPOSIT","C","50","0","50","2026-01-15"),
            txn(3,OLD,"INTEREST","C","5","100","105","2026-01-31"),
            txn(4,OLD,"INTEREST","C","2","105","105","2026-01-31"),
            txn(5,OLD,"RENEWAL_TRANSFER","D","105","105","0","2026-02-01"),
            txn(6,NEW,"RENEWAL_TRANSFER","C","105","0","105","2026-02-01"));
        when(store.businessDate()).thenReturn(d("2026-04-15"));
        when(store.accounts("","")).thenReturn(accounts); when(store.transactions("","")).thenReturn(txns);
    }
    @Test void liabilityIsCurrentAndCurrencySeparatedWithPerAccountRounding() {
        var report=reports.generate(ReportType.TOTAL_LIABILITY,"","",null,null);
        assertThat(report.rows()).hasSize(2);
        assertThat(report.totals()).extracting(r->r.get("currency")).containsExactly("INR","USD");
        assertThat(report.totals().getFirst()).containsEntry("depositBalance","105.00").containsEntry("accruedInterest","1.24").containsEntry("totalLiability","106.24");
    }
    @Test void growthUsesHistoricalBalancesExcludesPaidOutInterestAndDoesNotCountRenewalsAsNewMoney() {
        var result=reports.generate(ReportType.MONTHLY_GROWTH,"","",d("2026-01-01"),d("2026-02-28"));
        assertThat(result.rows()).hasSize(4);
        assertThat(result.rows().getFirst()).containsEntry("openingBalance","0.00").containsEntry("closingBalance","105.00").containsEntry("newDeposits","100.00").containsEntry("growthPercent",null);
        assertThat(result.rows().get(2)).containsEntry("openingBalance","105.00").containsEntry("closingBalance","105.00").containsEntry("netGrowth","0.00")
            .containsEntry("renewalIn","105.00").containsEntry("renewalOut","105.00").containsEntry("newDeposits","0.00").containsEntry("accountsClosed",1L);
    }
    @Test void partialMonthStartsWithPriorDayBalance() {
        var result=reports.generate(ReportType.MONTHLY_GROWTH,"","",d("2026-01-16"),d("2026-01-31"));
        assertThat(result.rows().getFirst()).containsEntry("openingBalance","100.00").containsEntry("closingBalance","105.00").containsEntry("growthPercent","5.0000");
    }
    @Test void statementIncludesPaidInterestWithoutIncreasingRunningBalance() {
        when(store.accounts("C1",OLD)).thenReturn(List.of(accounts.getFirst()));
        when(store.transactions("C1",OLD)).thenReturn(txns.stream().filter(t->t.accountId().equals(OLD)).toList());
        var result=reports.generate(ReportType.ACCOUNT_STATEMENT,"C1",OLD,d("2026-01-31"),d("2026-01-31"));
        assertThat(result.rows()).hasSize(2);
        assertThat(result.rows().getLast()).containsEntry("amount","2.00").containsEntry("balanceChange","0.00").containsEntry("runningBalance","105.00");
        assertThat(result.totals().getFirst()).containsEntry("openingBalance","100.00").containsEntry("closingBalance","105.00");
    }
    @Test void tdsUsesRecordedDirectionAndFinancialYearAndWarnsOnMissingSnapshots() {
        when(store.transactions("","")).thenReturn(List.of(
            new Txn(7,OLD,"TDS","D",b("10"),b("105"),b("95"),d("2026-03-31"),"ABCDE1234F",b("10"),null),
            new Txn(8,OLD,"TDS","D",b("5"),b("95"),b("90"),d("2026-04-01"),"ABCDE1234F",b("10"),null),
            new Txn(9,OLD,"TDS","C",b("2"),b("90"),b("92"),d("2026-04-02"),null,null,null)));
        var result=reports.generate(ReportType.TDS_COMPLIANCE,"","",d("2026-03-31"),d("2026-04-15"));
        assertThat(result.totals()).hasSize(2);
        assertThat(result.totals().getFirst()).containsEntry("financialYear","2025-2026").containsEntry("netDeduction","10.00");
        assertThat(result.totals().getLast()).containsEntry("financialYear","2026-2027").containsEntry("netDeduction","3.00");
        assertThat(result.warnings()).anySatisfy(w -> assertThat(w).contains("Missing PAN"));
        assertThat(reports.generate(ReportType.TDS_COMPLIANCE,"","",null,null).fromDate()).isEqualTo(d("2026-04-01"));
    }
    @Test void invalidRangesTypesAndMissingAccountsFailClearly() {
        assertThatThrownBy(()->reports.generate(ReportType.TOTAL_LIABILITY,"","",d("2026-01-01"),null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(()->reports.generate(ReportType.MONTHLY_GROWTH,"","",d("2026-03-01"),d("2026-02-01"))).hasMessageContaining("fromDate");
        assertThatThrownBy(()->reports.generate(ReportType.PORTFOLIO_SUMMARY,"","",null,d("2026-04-16"))).hasMessageContaining("business date");
        assertThatThrownBy(()->reports.generate(ReportType.ACCOUNT_STATEMENT,"","",null,null)).hasMessageContaining("accountId");
        when(store.accounts("",OLD)).thenReturn(List.of());
        assertThatThrownBy(()->reports.generate(ReportType.ACCOUNT_STATEMENT,"",OLD,null,null)).hasMessageContaining("not found");
    }
    @Test void incompleteHistoricalSnapshotsAndMissingHistoriesAreNeverInvented() {
        when(store.transactions("","")).thenReturn(List.of(txn(1,OLD,"DEPOSIT","C","100",null,"100","2026-01-01")));
        assertThatThrownBy(()->reports.generate(ReportType.MONTHLY_GROWTH,"","",null,null)).hasMessageContaining("Missing balance");
        when(store.transactions("","")).thenReturn(List.of());
        assertThatThrownBy(()->reports.generate(ReportType.MONTHLY_GROWTH,"","",null,null)).hasMessageContaining("No ledger history");
    }
    @Test void maturityUsesActiveAccountsAndAllowsFutureDates() {
        var result=reports.generate(ReportType.MATURITY_SCHEDULE,"","",d("2027-01-01"),d("2027-01-01"));
        assertThat(result.rows()).hasSize(2).allMatch(row->row.get("status").equals("ACTIVE"));
    }
    @Test void portfolioDatesFilterOpenDateAndEmptyResultsRemainEmpty() {
        assertThat(reports.generate(ReportType.PORTFOLIO_SUMMARY,"","",d("2026-02-01"),null).rows()).hasSize(1);
        when(store.accounts("","")).thenReturn(List.of());
        var result=reports.generate(ReportType.TOTAL_LIABILITY,"","",null,null);
        assertThat(result.rows()).isEmpty(); assertThat(result.totals()).isEmpty();
    }
    @Test void defaultGrowthWindowCrossesCalendarYear() {
        var result=reports.generate(ReportType.MONTHLY_GROWTH,"","",null,null);
        assertThat(result.fromDate()).isEqualTo(d("2025-05-01")); assertThat(result.toDate()).isEqualTo(d("2026-04-15"));
        assertThat(result.rows()).hasSize(24);
    }
}
