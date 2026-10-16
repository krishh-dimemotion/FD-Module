package com.banklab.reportservice;
import static org.assertj.core.api.Assertions.*;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.Test;
class CsvReportsTest {
    @Test void escapesFormulasQuotesNewlinesAndPreservesNegativeDecimals() {
        assertThat(CsvReports.escape(" =HYPERLINK(\"evil\")")).isEqualTo("\"' =HYPERLINK(\"\"evil\"\")\"");
        assertThat(CsvReports.escape("a,b\nc")).isEqualTo("\"a,b\nc\"");
        assertThat(CsvReports.escape("-12.34")).isEqualTo("\"-12.34\"");
        assertThat(CsvReports.escape("\t=1")).startsWith("\"'");
    }
    @Test void includesRowsTotalsMetadataAndWarningsWithStableEmptyHeaders() {
        var result=new ReportResult(ReportType.ACCOUNT_STATEMENT,Instant.EPOCH,LocalDate.parse("2026-01-01"),null,null,
            List.of(ReportService.row("amount","5.00")),List.of(ReportService.row("closingBalance","5.00")),List.of("Missing snapshot"));
        String csv=CsvReports.render(result);
        assertThat(csv).contains("openingBalance,closingBalance\r\n","\"ROW\"","\"TOTAL\"","\"WARNING\"","\"5.00\"");
        var empty=new ReportResult(ReportType.TDS_COMPLIANCE,Instant.EPOCH,result.businessDate(),null,null,List.of(),List.of(),List.of());
        assertThat(CsvReports.render(empty)).contains("financialYear,pan,taxRate,netDeduction", "\"METADATA\"");
    }
}
