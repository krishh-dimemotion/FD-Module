package com.banklab.fdservice.tax;

import java.math.BigDecimal;
import java.util.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;
import lombok.Getter;
import lombok.Setter;

/** All values are lab policy, not statutory tax advice. Overrides are keyed by FY start year. */
@Getter @Setter @Component @ConfigurationProperties(prefix="fd.tax")
public class TaxProperties {
    private boolean enabled = true;
    private int yearStartMonth = 4;
    private BigDecimal rate = new BigDecimal("10.0000");
    private Map<String,BigDecimal> allowances = new HashMap<>();
    private Set<String> exemptCustomers = new HashSet<>();
    private Map<Integer,Rule> years = new HashMap<>();
    @Getter @Setter
    public static class Rule {
        private String version;
        private BigDecimal rate;
        private Map<String,BigDecimal> allowances = new HashMap<>();
        private Set<String> exemptCustomers;
    }
    @jakarta.annotation.PostConstruct
    void validate() {
        if (yearStartMonth < 1 || yearStartMonth > 12) throw new IllegalArgumentException("fd.tax.year-start-month must be 1..12");
        validateRate(rate);
        allowances.forEach((c,v)->validateAllowance(c,v));
        years.forEach((year,r)-> { if(r.getRate()!=null) validateRate(r.getRate()); r.getAllowances().forEach((c,v)->validateAllowance(c,v)); });
    }
    private static void validateRate(BigDecimal v) {
        if(v==null || v.signum()<0 || v.compareTo(new BigDecimal("100"))>0 || v.scale()>4)
            throw new IllegalArgumentException("Demo tax rate must be 0..100 with at most four decimals");
    }
    private static void validateAllowance(String c,BigDecimal v) {
        int digits=com.banklab.fdservice.service.CurrencyDecimals.forCurrency(c);
        if(v==null || v.signum()<0 || v.stripTrailingZeros().scale()>digits || v.precision()-v.scale()>16)
            throw new IllegalArgumentException("Invalid demo allowance for " + c);
    }
}
