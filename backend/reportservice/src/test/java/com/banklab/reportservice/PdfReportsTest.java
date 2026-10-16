package com.banklab.reportservice;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;

class PdfReportsTest {
    private ReportResult sample(ReportType type,List<Map<String,Object>> rows,List<Map<String,Object>> totals) {
        return new ReportResult(type,Instant.parse("2026-10-16T00:00:00Z"),LocalDate.parse("2026-10-16"),
            LocalDate.parse("2026-01-01"),LocalDate.parse("2026-10-16"),rows,totals,List.of("Synthetic verification data"));
    }
    @Test void allSixTypesProduceSearchablePdfsWithMetadataAndEmptyStates() throws Exception {
        for(var type:ReportType.values()) {
            byte[] bytes=PdfReports.render(sample(type,List.of(),List.of()),Map.of("Customer","FILTER-CUSTOMER"));
            assertThat(new String(bytes,0,5,java.nio.charset.StandardCharsets.US_ASCII)).isEqualTo("%PDF-");
            try(var pdf=Loader.loadPDF(bytes)) {
                String text=new PDFTextStripper().getText(pdf);
                assertThat(text).contains("2026-10-16","FILTER-CUSTOMER","No matching records","Synthetic verification data","1 / 1");
                if(type==ReportType.TDS_COMPLIANCE) assertThat(text).contains("not a statutory tax certificate");
            }
        }
    }
    @Test void multipageWideTablesRetainEveryRowFieldAndCurrencyPrecision() throws Exception {
        List<Map<String,Object>> rows=new ArrayList<>();
        for(int i=0;i<65;i++) rows.add(ReportService.row("transactionId","REF-"+i,"accountNumber","FD-TEST-"+i,
            "currency","KWD","amount","1234.567","runningBalance","9007199254740991.123",
            "remarks","Verification narrative repeated across a deliberately long wrapped cell. ".repeat(3),
            "policyVersion","DEMO-2026","date","2026-10-16","taxRate","10.0000","netDeduction","123.457"));
        var report=sample(ReportType.ACCOUNT_STATEMENT,rows,List.of(ReportService.row("currency","INR","closingBalance","1234567.89")));
        byte[] bytes=PdfReports.render(report,Map.of());
        try(var pdf=Loader.loadPDF(bytes)) {
            assertThat(pdf.getNumberOfPages()).isGreaterThan(3);
            String text=new PDFTextStripper().getText(pdf).replace("\r","");
            assertThat(text).contains("REF-0","REF-64","FD-TEST-64","1,234.567","9,007,199,254,740,991.123","12,34,567.89","DEMO-2026");
            for(int i=0;i<pdf.getNumberOfPages();i++) {
                var stripper=new PDFTextStripper();stripper.setStartPage(i+1);stripper.setEndPage(i+1);
                assertThat(stripper.getText(pdf)).contains("FD Lab | "+(i+1)+" / ");
            }
        }
    }
    @Test void currencyDisplayUsesIsoDigitsAndIndianGrouping() {
        assertThat(MoneyDisplay.amount("1234567.891","INR")).isEqualTo("12,34,567.89");
        assertThat(MoneyDisplay.amount("1234567.5","JPY")).isEqualTo("1,234,568");
        assertThat(MoneyDisplay.amount("1234.5675","BHD")).isEqualTo("1,234.568");
    }
}
