package com.banklab.reportservice;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
public record ReportResult(ReportType reportType, Instant generatedAt, LocalDate businessDate, LocalDate fromDate,
    LocalDate toDate, List<Map<String,Object>> rows, List<Map<String,Object>> totals, List<String> warnings) { }
