package com.banklab.reportservice;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import com.banklab.reportservice.ReportStore.Account;
import com.banklab.reportservice.ReportStore.Txn;

@Service
public class ReportService {
    private final ReportStore store;
    public ReportService(ReportStore store) { this.store=store; }

    @Transactional(readOnly=true, isolation=Isolation.REPEATABLE_READ)
    public ReportResult generate(ReportType type, String customer, String accountId, LocalDate from, LocalDate to) {
        if (type==ReportType.ACCOUNT_STATEMENT && accountId.isBlank()) throw ApiException.invalid("accountId is required for account statements");
        if (!accountId.isBlank()) {
            try { UUID.fromString(accountId); } catch (IllegalArgumentException e) { throw ApiException.invalid("accountId must be a UUID"); }
        }
        if (type==ReportType.TOTAL_LIABILITY && (from!=null || to!=null)) throw ApiException.invalid("Total liability is the current position; date filters are unsupported");
        LocalDate today=store.businessDate();
        List<Account> accounts=store.accounts(customer,accountId);
        if (!accountId.isBlank() && accounts.isEmpty()) throw new ApiException(404,"NOT_FOUND","Account not found for the selected customer");
        LocalDate defaultFrom=switch(type) {
            case MONTHLY_GROWTH -> today.withDayOfMonth(1).minusMonths(11);
            case TDS_COMPLIANCE -> LocalDate.of(today.getMonthValue()<4 ? today.getYear()-1 : today.getYear(),4,1);
            case MATURITY_SCHEDULE -> today;
            case ACCOUNT_STATEMENT -> accounts.getFirst().opened();
            default -> null;
        };
        from=from==null ? defaultFrom : from;
        to=to==null ? (type==ReportType.MATURITY_SCHEDULE ? today.plusMonths(12) : today) : to;
        if (from!=null && from.isAfter(to)) throw ApiException.invalid("fromDate must not be after toDate");
        if (type!=ReportType.MATURITY_SCHEDULE && to.isAfter(today)) throw ApiException.invalid("toDate must not be after the business date");
        List<Map<String,Object>> rows=new ArrayList<>(), totals=new ArrayList<>();
        Set<String> warnings=new LinkedHashSet<>();
        switch(type) {
            case PORTFOLIO_SUMMARY -> {
                for (Account a:accounts) if (inside(a.opened(),from,to)) rows.add(accountRow(a,warnings));
                totals.addAll(accountTotals(rows));
            }
            case TOTAL_LIABILITY -> {
                for (Account a:accounts) if ("ACTIVE".equals(a.status())) rows.add(accountRow(a,warnings));
                totals.addAll(accountTotals(rows));
            }
            case MATURITY_SCHEDULE -> {
                for (Account a:accounts) if ("ACTIVE".equals(a.status()) && inside(a.maturity(),from,to)) rows.add(accountRow(a,warnings));
                rows.sort(Comparator.comparing(row -> row.get("maturityDate").toString()));
                totals.addAll(accountTotals(rows));
            }
            case TDS_COMPLIANCE -> {
                warnings.add("Demo tax summary: recorded withholding only; not a statutory tax certificate. Financial year uses settlement value date for engine deductions.");
                Map<String,Account> byId=index(accounts);
                for (Txn t:store.transactions(customer,accountId)) if ("TDS".equals(t.type()) && inside(t.date(),from,to)) {
                    Account a=byId.get(t.accountId());
                    if (t.policyVersion()==null && (t.pan()==null || t.taxRate()==null)) warnings.add("Missing PAN or tax-rate snapshot on TDS transaction " + t.id());
                    int fy=t.fiscalYear()!=null?t.fiscalYear():(t.date().getMonthValue()<4 ? t.date().getYear()-1 : t.date().getYear());
                    rows.add(row("transactionId",t.id(),"accountId",a.id(),"accountNumber",a.number(),"customerId",a.customerId(),
                        "customerName",a.customerName(),"currency",a.currency(),"date",t.date(),"financialYear",fy+"-"+(fy+1),
                        "policyVersion",t.policyVersion(),"taxableAmount",t.taxableAmount()==null?null:money(t.taxableAmount(),a.decimals()),
                        "pan",t.pan(),"taxRate",t.taxRate()==null?null:t.taxRate().toPlainString(),
                        "netDeduction",money("D".equals(t.direction())?t.amount():t.amount().negate(),a.decimals())));
                }
                Map<String,Map<String,Object>> grouped=new TreeMap<>();
                for (Map<String,Object> r:rows) {
                    String key=r.get("customerId")+"|"+r.get("financialYear")+"|"+r.get("currency");
                    Map<String,Object> total=grouped.computeIfAbsent(key,k->row("customerId",r.get("customerId"),"financialYear",r.get("financialYear"),"currency",r.get("currency"),"netDeduction","0"));
                    total.put("netDeduction",decimal(total.get("netDeduction")).add(decimal(r.get("netDeduction"))).toPlainString());
                }
                totals.addAll(grouped.values());
            }
            case MONTHLY_GROWTH, ACCOUNT_STATEMENT -> {
                List<Txn> txns=store.transactions(customer,accountId);
                validateHistory(accounts,txns,to);
                if (type==ReportType.ACCOUNT_STATEMENT) {
                    Account a=accounts.getFirst();
                    BigDecimal opening=balanceAt(txns,from.minusDays(1)), running=opening;
                    for (Txn t:txns) if (inside(t.date(),from,to)) {
                        running=running.add(t.delta());
                        rows.add(row("transactionId",t.id(),"date",t.date(),"type",t.type(),"direction",t.direction(),
                            "amount",money(t.amount(),a.decimals()),"balanceChange",money(t.delta(),a.decimals()),
                            "runningBalance",money(running,a.decimals()),"currency",a.currency(),"remarks",t.remarks()));
                    }
                    totals.add(row("accountId",a.id(),"accountNumber",a.number(),"customerId",a.customerId(),"customerName",a.customerName(),
                        "currency",a.currency(),"openingBalance",money(opening,a.decimals()),"closingBalance",money(running,a.decimals())));
                } else {
                    Map<String,Account> byId=index(accounts);
                    Set<String> currencies=new TreeSet<>(); accounts.forEach(a->currencies.add(a.currency()));
                    for (LocalDate month=from.withDayOfMonth(1); !month.isAfter(to); month=month.plusMonths(1)) {
                        LocalDate start=month.isBefore(from)?from:month, end=YearMonth.from(month).atEndOfMonth();
                        if (end.isAfter(to)) end=to;
                        for (String currency:currencies) {
                            List<Txn> cc=txns.stream().filter(t->currency.equals(byId.get(t.accountId()).currency())).toList();
                            BigDecimal opening=balanceAt(cc,start.minusDays(1)), closing=balanceAt(cc,end), deposits=BigDecimal.ZERO,
                                closurePayouts=BigDecimal.ZERO, renewalIn=BigDecimal.ZERO, renewalOut=BigDecimal.ZERO;
                            for (Txn t:cc) if (inside(t.date(),start,end)) {
                                if ("DEPOSIT".equals(t.type())) deposits=deposits.add(t.delta());
                                if (Set.of("WITHDRAWAL","MATURITY_PAYOUT").contains(t.type())) closurePayouts=closurePayouts.subtract(t.delta());
                                if ("RENEWAL_TRANSFER".equals(t.type())) {
                                    if ("C".equals(t.direction())) renewalIn=renewalIn.add(t.amount()); else renewalOut=renewalOut.add(t.amount());
                                }
                            }
                            int decimals=accounts.stream().filter(a->currency.equals(a.currency())).findFirst().orElseThrow().decimals();
                            BigDecimal change=closing.subtract(opening);
                            LocalDate intervalStart=start, intervalEnd=end;
                            long opened=accounts.stream().filter(a->currency.equals(a.currency())&&inside(a.opened(),intervalStart,intervalEnd)).count();
                            long closed=accounts.stream().filter(a->currency.equals(a.currency())&&inside(a.closed(),intervalStart,intervalEnd)).count();
                            rows.add(row("month",YearMonth.from(month).toString(),"fromDate",start,"toDate",end,"currency",currency,
                                "openingBalance",money(opening,decimals),"closingBalance",money(closing,decimals),"netGrowth",money(change,decimals),
                                "growthPercent",opening.signum()==0?null:change.multiply(new BigDecimal("100")).divide(opening,4,RoundingMode.HALF_UP).toPlainString(),
                                "newDeposits",money(deposits,decimals),"closurePayouts",money(closurePayouts,decimals),
                                "renewalIn",money(renewalIn,decimals),"renewalOut",money(renewalOut,decimals),"accountsOpened",opened,"accountsClosed",closed));
                        }
                    }
                }
            }
        }
        return new ReportResult(type,Instant.now(),today,from,to,rows,totals,List.copyOf(warnings));
    }
    private static void validateHistory(List<Account> accounts,List<Txn> txns,LocalDate cutoff) {
        Set<String> seen=new HashSet<>();
        for (Txn t:txns) if (!t.date().isAfter(cutoff)) { t.delta(); seen.add(t.accountId()); }
        for (Account a:accounts) if (a.opened()!=null && !a.opened().isAfter(cutoff)) {
            if (!seen.contains(a.id())) throw new ApiException(422,"INCOMPLETE_LEDGER_HISTORY","No ledger history for account " + a.number());
            BigDecimal expected=BigDecimal.ZERO;
            // Posting IDs preserve ledger execution order even if business dates were backdated.
            for (Txn t:txns.stream().filter(t->t.accountId().equals(a.id())).sorted(Comparator.comparingLong(Txn::id)).toList()) {
                t.delta();
                if (t.before().compareTo(expected)!=0) throw new ApiException(422,"INCOMPLETE_LEDGER_HISTORY","Broken balance chain on account " + a.number());
                expected=t.after();
            }
        }
    }
    private static BigDecimal balanceAt(List<Txn> txns,LocalDate date) {
        return txns.stream().filter(t->!t.date().isAfter(date)).map(Txn::delta).reduce(BigDecimal.ZERO,BigDecimal::add);
    }
    private static Map<String,Account> index(List<Account> accounts) {
        Map<String,Account> result=new HashMap<>(); accounts.forEach(a->result.put(a.id(),a)); return result;
    }
    private static Map<String,Object> accountRow(Account a,Set<String> warnings) {
        if (a.customerName()==null || a.productName()==null) warnings.add("Missing display snapshot on account " + a.number());
        if(a.taxEnabled()) warnings.add("Tax-enabled maturity amounts are net estimates; other account activity can change annual allowance availability.");
        if (a.balance()==null || a.accrued()==null) throw new ApiException(422,"INCOMPLETE_ACCOUNT_BALANCE","Missing current balance on account " + a.number());
        BigDecimal accrued=a.accrued().setScale(a.decimals(),RoundingMode.HALF_UP);
        return row("accountId",a.id(),"accountNumber",a.number(),"customerId",a.customerId(),"customerName",a.customerName(),
            "productCode",a.productCode(),"productName",a.productName(),"currency",a.currency(),"status",a.status(),
            "openDate",a.opened(),"maturityDate",a.maturity(),"principal",money(a.principal(),a.decimals()),
            "depositBalance",money(a.balance(),a.decimals()),"accruedInterest",money(accrued,a.decimals()),
            "totalLiability",money(a.balance().add(accrued),a.decimals()),"taxEnabled",a.taxEnabled(),"projectedMaturityAmount",a.maturityAmount()==null?null:money(a.maturityAmount(),a.decimals()));
    }
    private static List<Map<String,Object>> accountTotals(List<Map<String,Object>> rows) {
        Map<String,Map<String,Object>> totals=new TreeMap<>();
        for (Map<String,Object> r:rows) {
            Map<String,Object> t=totals.computeIfAbsent(r.get("currency").toString(),c->row("currency",c,"accountCount",0L,
                "depositBalance","0","accruedInterest","0","totalLiability","0"));
            t.put("accountCount",(Long)t.get("accountCount")+1);
            for (String field:List.of("depositBalance","accruedInterest","totalLiability")) t.put(field,decimal(t.get(field)).add(decimal(r.get(field))).toPlainString());
        }
        return new ArrayList<>(totals.values());
    }
    static boolean inside(LocalDate date,LocalDate from,LocalDate to) { return date!=null&&(from==null||!date.isBefore(from))&&(to==null||!date.isAfter(to)); }
    static BigDecimal decimal(Object value) { return new BigDecimal(value.toString()); }
    static String money(BigDecimal value,int decimals) { return value.setScale(decimals,RoundingMode.HALF_UP).toPlainString(); }
    static Map<String,Object> row(Object... pairs) {
        Map<String,Object> row=new LinkedHashMap<>(); for (int i=0;i<pairs.length;i+=2) row.put(pairs[i].toString(),pairs[i+1]); return row;
    }
}
