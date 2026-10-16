package com.banklab.reportservice;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ReportStore {
    private final NamedParameterJdbcTemplate jdbc;
    public ReportStore(NamedParameterJdbcTemplate jdbc) { this.jdbc=jdbc; }
    public LocalDate businessDate() {
        return jdbc.queryForObject("SELECT FDBC_BUSINESS_DT FROM FD_BUSINESS_CLOCK WHERE FDBC_ENTITY_CD='FD'", Map.of(), LocalDate.class);
    }
    // EXISTS avoids multiplying accounts/transactions when multiple matching roles exist.
    private static final String FILTER = """
        WHERE (:custId='' OR a.CUST_ID=:custId OR EXISTS
          (SELECT 1 FROM FD_ACCOUNT_ROLES r WHERE r.FDA_ID=a.FDA_ID AND r.CUST_ID=:custId AND r.FDRL_ROLE_TYP='OWNER'))
        AND (:accountId='' OR a.FDA_ID=:accountId)
        """;
    public List<Account> accounts(String custId, String accountId) {
        return jdbc.query("SELECT a.* FROM FD_ACCOUNTS a " + FILTER + " ORDER BY a.FDA_ACCT_NUM, a.FDA_ID",
            Map.of("custId",custId,"accountId",accountId), (rs,n) -> new Account(
                rs.getString("FDA_ID"),rs.getString("FDA_ACCT_NUM"),rs.getString("CUST_ID"),
                rs.getString("FDA_CUST_NAME_SNAP"),rs.getString("FDA_PRD_CODE"),rs.getString("FDA_PRD_NAME_SNAP"),
                rs.getString("FDA_CCY_CD"),decimals(rs.getObject("FDA_CCY_DECIMALS",Integer.class),rs.getString("FDA_CCY_CD")),rs.getString("FDA_STS"),
                rs.getObject("FDA_OPEN_DT",LocalDate.class),rs.getObject("FDA_MAT_DT",LocalDate.class),
                rs.getObject("FDA_CLOSURE_DT",LocalDate.class),rs.getBigDecimal("FDA_PRINCIPAL_AMT"),
                rs.getBigDecimal("FDA_PRINCIPAL_BAL"),rs.getBigDecimal("FDA_ACCRUED_INT_AMT"),rs.getBigDecimal("FDA_MAT_AMT")));
    }
    public List<Txn> transactions(String custId, String accountId) {
        return jdbc.query("SELECT t.* FROM FD_TRANSACTIONS t JOIN FD_ACCOUNTS a ON a.FDA_ID=t.FDA_ID " + FILTER + " ORDER BY t.FDT_TXN_DT,t.FDT_ID",
            Map.of("custId",custId,"accountId",accountId), (rs,n) -> new Txn(rs.getLong("FDT_ID"),rs.getString("FDA_ID"),
                rs.getString("FDT_TXN_TYP"),rs.getString("FDT_DR_CR"),rs.getBigDecimal("FDT_AMT"),
                rs.getBigDecimal("FDT_BAL_BEFORE"),rs.getBigDecimal("FDT_BAL_AFTER"),rs.getObject("FDT_TXN_DT",LocalDate.class),
                rs.getString("FDT_PAN_SNAP"),rs.getBigDecimal("FDT_TDS_RT"),rs.getString("FDT_REMARKS")));
    }
    private static int decimals(Integer snapshot,String currency) {
        int digits=snapshot==null?java.util.Currency.getInstance(currency).getDefaultFractionDigits():snapshot;
        if (digits<0 || digits>4) throw new ApiException(422,"INVALID_CURRENCY_PRECISION","Unsupported precision for " + currency);
        return digits;
    }
    public record Account(String id,String number,String customerId,String customerName,String productCode,String productName,
        String currency,int decimals,String status,LocalDate opened,LocalDate maturity,LocalDate closed,
        BigDecimal principal,BigDecimal balance,BigDecimal accrued,BigDecimal maturityAmount) { }
    public record Txn(long id,String accountId,String type,String direction,BigDecimal amount,BigDecimal before,BigDecimal after,
        LocalDate date,String pan,BigDecimal taxRate,String remarks) {
        BigDecimal delta() {
            if (before==null || after==null) throw new ApiException(422,"INCOMPLETE_LEDGER_HISTORY","Missing balance snapshots on transaction " + id);
            return after.subtract(before);
        }
    }
}
