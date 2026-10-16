package com.banklab.reportservice;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class ReportController {
    private final ReportService reports;
    private final ReportStore store;
    private final ExchangeRates fx;
    public ReportController(ReportService reports,ReportStore store,ExchangeRates fx) { this.reports=reports;this.store=store;this.fx=fx; }
    @GetMapping("/reports/fd-accounts")
    public ResponseEntity<?> report(@RequestParam ReportType reportType,@RequestParam(defaultValue="") String custId,
        @RequestParam(defaultValue="") String accountId,@RequestParam(required=false) LocalDate fromDate,
        @RequestParam(required=false) LocalDate toDate,@RequestParam(defaultValue="json") String format) {
        if (!format.equals("json")&&!format.equals("csv")) throw ApiException.invalid("format must be json or csv");
        ReportResult result=reports.generate(reportType,custId.trim(),accountId.trim(),fromDate,toDate);
        if (format.equals("csv")) return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
            .header(HttpHeaders.CONTENT_DISPOSITION,"attachment; filename=\""+reportType.name().toLowerCase(java.util.Locale.ROOT)+".csv\"")
            .header(HttpHeaders.CACHE_CONTROL,"no-store").body(CsvReports.render(result));
        return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL,"no-store").body(result);
    }
    @GetMapping("/exchange-rates")
    public Map<String,Object> rates(@RequestParam(defaultValue="INR") String base,@RequestParam(required=false) LocalDate date) {
        return fx.lookup(base,date==null?store.businessDate():date);
    }
    @GetMapping("/exchange-rates/convert")
    public Map<String,Object> convert(@RequestParam String from,@RequestParam String to,@RequestParam BigDecimal amount,
        @RequestParam(required=false) LocalDate date) {
        return fx.convert(from,to,amount,date==null?store.businessDate():date);
    }
    @GetMapping("/health")
    public Map<String,Object> health() { return Map.of("status","UP","businessDate",store.businessDate()); }
}
