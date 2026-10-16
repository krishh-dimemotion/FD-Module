package com.banklab.reportservice;

import static org.assertj.core.api.Assertions.*;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.ByteArrayResource;

class ExchangeRatesTest {
    final ExchangeRates fx;
    ExchangeRatesTest() throws Exception { fx=new ExchangeRates(new ClassPathResource("demo-rates.properties")); }
    @Test void selectsLatestPastSetAndComputesCrossRates() {
        assertThat(fx.lookup("usd",LocalDate.parse("2026-09-30"))).containsEntry("effectiveDate",LocalDate.parse("2026-01-01")).containsEntry("source","DEMO");
        assertThat(fx.lookup("INR",LocalDate.parse("2026-10-01"))).containsEntry("effectiveDate",LocalDate.parse("2026-10-01"));
        assertThat(fx.convert("USD","EUR",new BigDecimal("100"),LocalDate.parse("2026-01-01"))).containsEntry("convertedAmount","91.67");
    }
    @Test void identityZeroAndHalfUpRounding() {
        var date=LocalDate.parse("2026-01-01");
        assertThat(fx.convert("INR","INR",new BigDecimal("1.005"),date)).containsEntry("convertedAmount","1.01");
        assertThat(fx.convert("USD","INR",BigDecimal.ZERO,date)).containsEntry("convertedAmount","0.00");
    }
    @Test void rejectsUnknownDatesCurrenciesAndInvalidAmounts() {
        assertThatThrownBy(()->fx.lookup("INR",LocalDate.parse("2025-12-31"))).hasMessageContaining("No demo rate");
        assertThatThrownBy(()->fx.lookup("JPY",LocalDate.parse("2026-01-01"))).hasMessageContaining("No demo rate");
        assertThatThrownBy(()->fx.lookup("NOPE",LocalDate.parse("2026-01-01"))).hasMessageContaining("three-letter");
        assertThatThrownBy(()->fx.convert("USD","INR",new BigDecimal("-1"),LocalDate.parse("2026-01-01"))).hasMessageContaining("nonnegative");
        assertThatThrownBy(()->fx.convert("USD","INR",new BigDecimal("1E+1000"),LocalDate.parse("2026-01-01"))).isInstanceOf(ApiException.class);
    }
    @Test void roundsUsingZeroAndThreeDecimalCurrencies() throws Exception {
        var special=new ExchangeRates(new ByteArrayResource("2026-01-01.INR=1\n2026-01-01.JPY=2\n2026-01-01.KWD=0.003\n".getBytes()));
        assertThat(special.convert("INR","JPY",new BigDecimal("1.25"),LocalDate.parse("2026-01-01"))).containsEntry("convertedAmount","3");
        assertThat(special.convert("INR","KWD",new BigDecimal("1.5"),LocalDate.parse("2026-01-01"))).containsEntry("convertedAmount","0.005");
    }
    @Test void invalidConfigurationFailsAtStartup() {
        assertThatThrownBy(()->new ExchangeRates(new ByteArrayResource("2026-01-01.INR=-1".getBytes()))).hasMessageContaining("positive");
        assertThatThrownBy(()->new ExchangeRates(new ByteArrayResource("2026-01-01.USD=1".getBytes()))).hasMessageContaining("INR=1");
    }
}
