package com.banklab.reportservice;
import static org.assertj.core.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties={"spring.datasource.url=jdbc:mysql://localhost:3306/report_db_test", "spring.datasource.username=fd_test",
    "spring.datasource.password=${REPORT_TEST_PASSWORD}","spring.datasource.hikari.read-only=false"})
@AutoConfigureMockMvc
class ReportApiIntegrationTest {
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;
    static final String ID="00000000-0000-0000-0000-000000000001";
    @BeforeEach void seed() {
        new ResourceDatabasePopulator(new ClassPathResource("schema.sql")).execute(Objects.requireNonNull(jdbc.getDataSource()));
        jdbc.update("INSERT INTO FD_BUSINESS_CLOCK VALUES ('FD','2026-04-15')");
        jdbc.update("INSERT INTO FD_ACCOUNTS VALUES (?, '0010000017','C1','Asha','FD-REG','Regular','INR',2,'ACTIVE','2026-01-01','2027-01-01',NULL,100,100,1.235,110,false)",ID);
        jdbc.update("INSERT INTO FD_ACCOUNT_ROLES VALUES (?,'OTHER','OWNER'),(?,'OTHER','OWNER'),(?,'NOMINEE','NOMINEE')",ID,ID,ID);
        jdbc.update("INSERT INTO FD_TRANSACTIONS VALUES (1,?,'DEPOSIT','C',100,0,100,'2026-01-01',NULL,NULL,'initial')",ID);
    }
    @Test void parameterizedCustomerFilterDeduplicatesOwnersAndExcludesNominees() throws Exception {
        mvc.perform(get("/reports/fd-accounts").param("reportType","TOTAL_LIABILITY").param("custId","OTHER"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.rows.length()").value(1)).andExpect(jsonPath("$.totals[0].totalLiability").value("101.24"));
        for (String customer:List.of("NOMINEE","C1' OR 1=1 --")) mvc.perform(get("/reports/fd-accounts").param("reportType","PORTFOLIO_SUMMARY").param("custId",customer))
            .andExpect(status().isOk()).andExpect(jsonPath("$.rows.length()").value(0));
    }
    @Test void reportAndCsvReadTheSameValuesWithoutChangingDatabase() throws Exception {
        var before=snapshot();
        for (String type:List.of("TOTAL_LIABILITY","PORTFOLIO_SUMMARY","MONTHLY_GROWTH","TDS_COMPLIANCE","MATURITY_SCHEDULE","ACCOUNT_STATEMENT")) {
            var request=get("/reports/fd-accounts").param("reportType",type);
            if (type.equals("ACCOUNT_STATEMENT")) request.param("accountId",ID);
            mvc.perform(request).andExpect(status().isOk());
            request.param("format","pdf");
            var pdf=mvc.perform(request).andExpect(status().isOk()).andExpect(content().contentType("application/pdf"))
                .andExpect(header().string("Cache-Control","no-store")).andReturn().getResponse().getContentAsByteArray();
            try(var document=org.apache.pdfbox.Loader.loadPDF(pdf)) { assertThat(document.getNumberOfPages()).isPositive(); }
        }
        mvc.perform(get("/reports/fd-accounts").param("reportType","TOTAL_LIABILITY").param("format","csv"))
            .andExpect(status().isOk()).andExpect(header().string("Content-Disposition","attachment; filename=\"total_liability.csv\""))
            .andExpect(content().string(org.hamcrest.Matchers.containsString("\"101.24\"")));
        assertThat(snapshot()).isEqualTo(before);
    }
    @Test void errorsAndExchangeRatesHaveDocumentedStatusCodes() throws Exception {
        mvc.perform(get("/reports/fd-accounts")).andExpect(status().isBadRequest());
        mvc.perform(get("/reports/fd-accounts").param("reportType","UNKNOWN")).andExpect(status().isBadRequest());
        mvc.perform(get("/reports/fd-accounts").param("reportType","PORTFOLIO_SUMMARY").param("fromDate","nonsense")).andExpect(status().isBadRequest());
        mvc.perform(get("/reports/fd-accounts").param("reportType","PORTFOLIO_SUMMARY").param("format","xlsx")).andExpect(status().isBadRequest());
        mvc.perform(get("/reports/fd-accounts").param("reportType","ACCOUNT_STATEMENT").param("custId","NOMINEE").param("accountId",ID)).andExpect(status().isNotFound());
        mvc.perform(get("/exchange-rates/convert").param("from","USD").param("to","INR").param("amount","100"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.source").value("DEMO")).andExpect(jsonPath("$.convertedAmount").value("8333.33"));
        mvc.perform(get("/exchange-rates").param("date","2025-01-01")).andExpect(status().isNotFound());
        mvc.perform(get("/exchange-rates").param("base","CHF")).andExpect(status().isNotFound());
        mvc.perform(get("/exchange-rates/convert").param("from","USD").param("to","INR").param("amount","NaN")).andExpect(status().isBadRequest());
    }
    @Test void missingBalanceSnapshotsFailInsteadOfMakingUpHistory() throws Exception {
        jdbc.update("UPDATE FD_TRANSACTIONS SET FDT_BAL_BEFORE=NULL");
        mvc.perform(get("/reports/fd-accounts").param("reportType","ACCOUNT_STATEMENT").param("accountId",ID))
            .andExpect(status().isUnprocessableContent()).andExpect(jsonPath("$.code").value("INCOMPLETE_LEDGER_HISTORY"));
    }
    Map<String,List<Map<String,Object>>> snapshot() {
        Map<String,List<Map<String,Object>>> result=new TreeMap<>();
        for (String table:List.of("FD_BUSINESS_CLOCK","FD_ACCOUNTS","FD_TRANSACTIONS","FD_ACCOUNT_ROLES")) result.put(table,jdbc.queryForList("SELECT * FROM " + table));
        return result;
    }
}
