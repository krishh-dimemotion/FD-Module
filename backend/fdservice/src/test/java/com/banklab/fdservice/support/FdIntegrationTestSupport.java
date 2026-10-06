package com.banklab.fdservice.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import com.banklab.fdservice.entity.FdAccount;
import com.banklab.fdservice.entity.FdAccountSequence;
import com.banklab.fdservice.entity.FdGlEntry;
import com.banklab.fdservice.entity.FdTransaction;
import com.banklab.fdservice.repository.FdAccountRepository;
import com.banklab.fdservice.repository.FdAccountSequenceRepository;
import com.banklab.fdservice.repository.FdGlEntryRepository;
import com.banklab.fdservice.repository.FdTransactionRepository;
import com.banklab.fdservice.service.BusinessClockService;
import com.banklab.fdservice.service.GlAccountService;
import com.jayway.jsonpath.JsonPath;

/**
 * Base for end-to-end tests: the full HTTP → controller → service → Spring Batch →
 * MySQL stack, with no mocks (Groups 1 and 2 are the in-app stubs, whose rate for any
 * rateId is 6.50%). Every test starts from an empty ledger, branch 001's sequence at
 * zero and the business clock on 2026-01-01.
 */
@SpringBootTest
@AutoConfigureMockMvc
public abstract class FdIntegrationTestSupport {

    protected static final LocalDate START = LocalDate.parse("2026-01-01");

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected FdAccountRepository accountRepository;

    @Autowired
    protected FdTransactionRepository transactionRepository;

    @Autowired
    protected FdGlEntryRepository glEntryRepository;

    @Autowired
    protected FdAccountSequenceRepository sequenceRepository;

    @Autowired
    protected BusinessClockService businessClock;

    @Autowired
    protected GlAccountService glAccounts;

    @BeforeEach
    void resetFdData() {
        new FdTestDatabase(jdbc).clearFdData();
        glAccounts.ensureDefaults();
        sequenceRepository.saveAndFlush(FdAccountSequence.builder()
                .branchCd("001")
                .branchName("Head Office")
                .prefix("FD")
                .lastSeq(0L)
                .seqWidth(6)
                .build());
        businessClock.setBusinessDate(START);
    }

    @AfterEach
    void clearFdData() {
        new FdTestDatabase(jdbc).clearFdData();
    }

    protected UUID createAccount(String requestJson) throws Exception {
        String body = mockMvc.perform(post("/fd-accounts").contentType(MediaType.APPLICATION_JSON).content(requestJson))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String fdaId = JsonPath.read(body, "$.fdaId");
        return UUID.fromString(fdaId);
    }

    /**
     * Runs a batch job the way EOD does: the business clock is first moved to that
     * date (a run may not be ahead of the clock).
     */
    protected ResultActions runBatch(String jobName, String businessDate) throws Exception {
        businessClock.setBusinessDate(LocalDate.parse(businessDate));
        return mockMvc.perform(post("/batch-jobs/{jobName}/runs", jobName)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"businessDate\":\"" + businessDate + "\"}"))
                .andExpect(status().isAccepted());
    }

    protected ResultActions withdraw(UUID fdaId, String body) throws Exception {
        return mockMvc.perform(post("/fd-accounts/{id}/withdraw", fdaId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    /**
     * Turns a freshly booked account into one booked before rateId was mandatory (such
     * rows still exist in fd_db): no rate snapshot, so it can't accrue but must still close.
     */
    protected void removeContractedRate(UUID fdaId) {
        jdbc.update("UPDATE FD_ACCOUNTS SET FDA_RATE_ID = NULL, FDA_INT_RT = NULL, FDA_MAT_AMT = NULL WHERE FDA_ID = ?",
                fdaId.toString());
    }

    protected FdAccount account(UUID fdaId) {
        return accountRepository.findById(fdaId).orElseThrow();
    }

    protected List<FdTransaction> transactions(UUID fdaId) {
        return transactionRepository.findByFdaIdOrderByFdtIdAsc(fdaId);
    }

    protected Map<String, BigDecimal> glBalances() {
        return jdbc.query("SELECT FDGL_CD, FDGL_CURRENT_BAL FROM FD_GL_ACCOUNTS", rs -> {
            Map<String, BigDecimal> balances = new HashMap<>();
            while (rs.next()) {
                balances.put(rs.getString(1), rs.getBigDecimal(2));
            }
            return balances;
        });
    }

    /**
     * FD_TRANSACTIONS ↔ FD_GL_ENTRIES reconciliation: every transaction has exactly one
     * debit and one credit leg of its own amount, and debits == credits per group.
     */
    protected void assertLedgerReconciles() {
        List<FdTransaction> allTxns = transactionRepository.findAll();
        // Daily accrual legs have no FD_TRANSACTIONS row (FDGE_FDT_ID null) and are checked by group below
        Map<Long, List<FdGlEntry>> legsByTxn = glEntryRepository.findAll().stream()
                .filter(leg -> leg.getFdtId() != null)
                .collect(Collectors.groupingBy(FdGlEntry::getFdtId));
        assertThat(allTxns).allSatisfy(txn -> {
            List<FdGlEntry> legs = legsByTxn.get(txn.getFdtId());
            assertThat(legs).as("GL legs of %s %s", txn.getTxnTyp(), txn.getFdtId()).hasSize(2);
            assertThat(legs).extracting(FdGlEntry::getDrCr).containsExactlyInAnyOrder("D", "C");
            assertThat(legs).allSatisfy(leg -> {
                assertThat(leg.getAmt()).isEqualByComparingTo(txn.getAmt());
                assertThat(leg.getTxnGrpId()).isEqualTo(txn.getTxnGrpId());
            });
        });
        glEntryRepository.findAll().stream()
                .collect(Collectors.groupingBy(FdGlEntry::getTxnGrpId))
                .forEach((group, legs) -> assertThat(sumOf(legs, "D"))
                        .as("debits == credits for group %s", group)
                        .isEqualByComparingTo(sumOf(legs, "C")));

        BigDecimal accruedOnActiveAccounts = accountRepository.findAll().stream()
                .filter(account -> "ACTIVE".equals(account.getSts()) && account.getAccruedIntAmt() != null)
                .map(account -> account.getAccruedIntAmt().setScale(
                        account.getCcyDecimals() == null ? 2 : account.getCcyDecimals(), java.math.RoundingMode.HALF_UP))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(glBalances().get("FD_INT_PAYABLE"))
                .as("FD_INT_PAYABLE == rounded accrued interest of ACTIVE accounts")
                .isEqualByComparingTo(accruedOnActiveAccounts);

        accountRepository.findAll().stream()
                .filter(account -> "CLOSED".equals(account.getSts()) || "MATURED_CLOSED".equals(account.getSts()))
                .forEach(account -> {
                    assertThat(account.getPrincipalBal()).isZero();
                    assertThat(account.getAccruedIntAmt()).isZero();
                    BigDecimal balance = BigDecimal.ZERO;
                    BigDecimal depositNet = BigDecimal.ZERO;
                    for (FdTransaction txn : transactions(account.getFdaId())) {
                        assertThat(txn.getBalBefore()).isEqualByComparingTo(balance);
                        BigDecimal externalInterest = "INTEREST".equals(txn.getTxnTyp())
                                ? legsByTxn.get(txn.getFdtId()).stream()
                                        .filter(leg -> "C".equals(leg.getDrCr()) && "FD_SETTLEMENT".equals(leg.getGlCd()))
                                        .map(FdGlEntry::getAmt).reduce(BigDecimal.ZERO, BigDecimal::add)
                                : BigDecimal.ZERO;
                        BigDecimal movement = ("C".equals(txn.getDrCr()) ? txn.getAmt() : txn.getAmt().negate())
                                .subtract(externalInterest);
                        assertThat(txn.getBalAfter()).isEqualByComparingTo(balance.add(movement));
                        balance = txn.getBalAfter();
                        depositNet = depositNet.add(movement);
                    }
                    assertThat(balance).isZero();
                    assertThat(depositNet).as("deposit credits less debits, excluding external interest payouts").isZero();
                });
    }

    protected static String accountJson(String principal, int tenureMonths, String rateId, String interestType,
            String compoundingFreq, String payoutFreq) {
        StringBuilder json = new StringBuilder("{")
                .append("\"custId\":\"CUST-001\",\"productCode\":\"FD-REG\",\"currencyCode\":\"INR\",")
                .append("\"maturityInstruction\":\"PAYOUT\",")
                .append("\"principal\":").append(principal)
                .append(",\"tenureMonths\":").append(tenureMonths);
        appendOptional(json, "rateId", rateId);
        appendOptional(json, "interestType", interestType);
        appendOptional(json, "compoundingFreq", compoundingFreq);
        appendOptional(json, "payoutFreq", payoutFreq);
        return json.append(",\"initialRoles\":[{\"custId\":\"CUST-001\",\"roleType\":\"OWNER\",\"isPrimary\":true}]}")
                .toString();
    }

    protected static LocalDate date(String iso) {
        return LocalDate.parse(iso);
    }

    private static void appendOptional(StringBuilder json, String field, String value) {
        if (value != null) {
            json.append(",\"").append(field).append("\":\"").append(value).append('"');
        }
    }

    private static BigDecimal sumOf(List<FdGlEntry> legs, String side) {
        return legs.stream().filter(leg -> side.equals(leg.getDrCr())).map(FdGlEntry::getAmt)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
