package com.banklab.fdservice.tax;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import com.banklab.fdservice.entity.FdAccount;

/** Preloads tax-year locks before any GL work; calculators consume only an in-memory copy. */
@Service
public class TaxService {
    private final JdbcTemplate jdbc;
    private final TaxProperties config;
    public TaxService(JdbcTemplate jdbc, TaxProperties config) { this.jdbc=jdbc; this.config=config; }
    public boolean enabledForNew() { return config.isEnabled(); }
    public Session session(FdAccount account, LocalDate from, LocalDate to, boolean write) {
        if (!Boolean.TRUE.equals(account.getTaxEnabled())) return Session.disabled();
        Map<Integer,Year> years=new TreeMap<>();
        int month=config.getYearStartMonth();
        // Fiscal calendar cannot change after an assessment; old FY assignments must remain stable.
        Integer incompatible=jdbc.queryForObject("SELECT COUNT(*) FROM FD_TAX_YEARS WHERE START_MONTH<>?",Integer.class,month);
        if(incompatible!=null && incompatible>0) throw new IllegalStateException("Tax year calendar is already frozen; restore fd.tax.year-start-month");
        for(int y=financialYear(from,month);y<=financialYear(to,month);y++) {
            String key=account.getCustId()+"|"+account.getCcyCd()+"|"+y;
            Year fallback=policy(key,account,y,month);
            if(write) jdbc.update("""
                INSERT IGNORE INTO FD_TAX_YEARS
                (TAX_KEY,CUSTOMER_ID,CURRENCY,FY_START,START_MONTH,POLICY_VERSION,TAX_RATE,ALLOWANCE,EXEMPT,GROSS_INTEREST,TAX_WITHHELD)
                VALUES (?,?,?,?,?,?,?,?,?,0,0)
                """,key,account.getCustId(),account.getCcyCd(),y,month,fallback.version,fallback.rate,fallback.allowance,fallback.exempt);
            List<Year> existing=jdbc.query("SELECT * FROM FD_TAX_YEARS WHERE TAX_KEY=?"+(write?" FOR UPDATE":""),
                (rs,n)->new Year(rs.getString("TAX_KEY"),rs.getString("POLICY_VERSION"),rs.getBigDecimal("TAX_RATE"),
                    rs.getBigDecimal("ALLOWANCE"),rs.getBoolean("EXEMPT"),rs.getBigDecimal("GROSS_INTEREST"),rs.getBigDecimal("TAX_WITHHELD")),key);
            years.put(y,existing.isEmpty()?fallback:existing.getFirst());
        }
        return new Session(jdbc,years,month,account.getCcyDecimals()==null?com.banklab.fdservice.service.CurrencyDecimals.forCurrency(account.getCcyCd()):account.getCcyDecimals(),write);
    }
    private Year policy(String key,FdAccount a,int y,int month) {
        TaxProperties.Rule override=config.getYears().get(y);
        BigDecimal rate=override!=null && override.getRate()!=null?override.getRate():config.getRate();
        Map<String,BigDecimal> allowances=new HashMap<>(config.getAllowances());
        if(override!=null) allowances.putAll(override.getAllowances());
        Set<String> exempt=override!=null && override.getExemptCustomers()!=null?override.getExemptCustomers():config.getExemptCustomers();
        String version=override!=null && override.getVersion()!=null?override.getVersion():"DEMO-"+y;
        if(version.length()>64) throw new IllegalArgumentException("Tax version exceeds 64 characters");
        return new Year(key,version,rate,allowances.getOrDefault(a.getCcyCd(),BigDecimal.ZERO),exempt.contains(a.getCustId()),BigDecimal.ZERO,BigDecimal.ZERO);
    }
    public static int financialYear(LocalDate date,int month) { return date.getMonthValue()<month?date.getYear()-1:date.getYear(); }
    private static class Year {
        final String key,version;
        final BigDecimal rate,allowance;
        final boolean exempt;
        BigDecimal gross,tax;
        Year(String key,String version,BigDecimal rate,BigDecimal allowance,boolean exempt,BigDecimal gross,BigDecimal tax) {
            this.key=key;this.version=version;this.rate=rate;this.allowance=allowance;this.exempt=exempt;this.gross=gross;this.tax=tax;
        }
    }
    public record Decision(String key,String version,LocalDate date,BigDecimal gross,BigDecimal taxable,BigDecimal rate,
            BigDecimal deduction,BigDecimal grossAfter,BigDecimal taxAfter) { }
    public static final class Session {
        private final JdbcTemplate jdbc;
        private final Map<Integer,Year> years;
        private final int month,decimals;
        private final boolean write;
        private final Deque<Decision> decisions=new ArrayDeque<>();
        private Session(JdbcTemplate jdbc,Map<Integer,Year> years,int month,int decimals,boolean write) {
            this.jdbc=jdbc;this.years=years;this.month=month;this.decimals=decimals;this.write=write;
        }
        public static Session disabled() { return new Session(null,Map.of(),4,2,false); }
        public BigDecimal assess(BigDecimal gross,LocalDate date) {
            if(gross.signum()==0 || years.isEmpty()) return BigDecimal.ZERO;
            Year y=years.get(financialYear(date,month));
            if(y==null) throw new IllegalStateException("Settlement outside prepared tax years");
            BigDecimal after=y.gross.add(gross);
            BigDecimal taxable=y.exempt?BigDecimal.ZERO:after.subtract(y.allowance).max(BigDecimal.ZERO)
                .subtract(y.gross.subtract(y.allowance).max(BigDecimal.ZERO));
            BigDecimal deduction=taxable.multiply(y.rate).divide(new BigDecimal("100"),decimals,RoundingMode.HALF_UP).min(gross);
            y.gross=after; y.tax=y.tax.add(deduction);
            decisions.add(new Decision(y.key,y.version,date,gross,taxable,y.rate,deduction,after,y.tax));
            return deduction;
        }
        public Decision next() { return decisions.pollFirst(); }
        public void record(Decision d,long sourceId,Long taxId) {
            if(d==null) return;
            if(!write) throw new IllegalStateException("Cannot persist a tax preview");
            jdbc.update("""
                INSERT INTO FD_TAX_EVENTS (SOURCE_FDT_ID,TAX_FDT_ID,TAX_KEY,POLICY_VERSION,VALUE_DATE,TAXABLE_AMOUNT,TAX_RATE,DEDUCTION)
                VALUES (?,?,?,?,?,?,?,?)
                """,sourceId,taxId,d.key,d.version,d.date,d.taxable,d.rate,d.deduction);
            jdbc.update("UPDATE FD_TAX_YEARS SET GROSS_INTEREST=?,TAX_WITHHELD=? WHERE TAX_KEY=?",d.grossAfter,d.taxAfter,d.key);
        }
    }
}
