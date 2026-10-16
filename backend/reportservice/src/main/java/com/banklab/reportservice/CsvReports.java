package com.banklab.reportservice;

import java.util.*;

public final class CsvReports {
    private CsvReports() { }
    public static List<String> fields(ReportType type) {
        return switch(type) {
            case PORTFOLIO_SUMMARY,TOTAL_LIABILITY,MATURITY_SCHEDULE -> List.of("accountId","accountNumber","customerId","customerName","productCode","productName","currency","status","openDate","maturityDate","principal","depositBalance","accruedInterest","totalLiability","projectedMaturityAmount","accountCount");
            case TDS_COMPLIANCE -> List.of("transactionId","accountId","accountNumber","customerId","customerName","currency","date","financialYear","pan","taxRate","netDeduction");
            case MONTHLY_GROWTH -> List.of("month","fromDate","toDate","currency","openingBalance","closingBalance","netGrowth","growthPercent","newDeposits","closurePayouts","renewalIn","renewalOut","accountsOpened","accountsClosed");
            case ACCOUNT_STATEMENT -> List.of("transactionId","date","type","direction","amount","balanceChange","runningBalance","currency","remarks","accountId","accountNumber","customerId","customerName","openingBalance","closingBalance");
        };
    }
    public static String render(ReportResult report) {
        List<String> fields=new ArrayList<>(List.of("section","reportType","generatedAt","businessDate","periodFrom","periodTo","warning"));
        fields.addAll(fields(report.reportType()));
        StringBuilder csv=new StringBuilder(String.join(",",fields)).append("\r\n");
        for (var row:report.rows()) append(csv,fields,report,"ROW",row,null);
        for (var row:report.totals()) append(csv,fields,report,"TOTAL",row,null);
        for (String warning:report.warnings()) append(csv,fields,report,"WARNING",Map.of(),warning);
        if (report.rows().isEmpty()&&report.totals().isEmpty()&&report.warnings().isEmpty()) append(csv,fields,report,"METADATA",Map.of(),null);
        return csv.toString();
    }
    private static void append(StringBuilder out,List<String> fields,ReportResult report,String section,Map<String,Object> row,String warning) {
        Map<String,Object> values=new LinkedHashMap<>(row);
        values.putAll(ReportService.row("section",section,"reportType",report.reportType(),"generatedAt",report.generatedAt(),
            "businessDate",report.businessDate(),"periodFrom",report.fromDate(),"periodTo",report.toDate(),"warning",warning));
        out.append(String.join(",",fields.stream().map(f->escape(values.get(f))).toList())).append("\r\n");
    }
    static String escape(Object value) {
        String text=value==null?"":value.toString(); String stripped=text.stripLeading();
        if (!text.matches("-?[0-9]+(?:\\.[0-9]+)?") && (!stripped.isEmpty() && "=+-@".indexOf(stripped.charAt(0))>=0 || text.startsWith("\t") || text.startsWith("\r") || text.startsWith("\n"))) text="'"+text;
        return "\""+text.replace("\"","\"\"")+"\"";
    }
}
