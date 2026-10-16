package com.banklab.fdservice.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import java.math.BigDecimal;
import java.time.LocalDate;

import org.junit.jupiter.api.Test;

import com.banklab.fdservice.dto.InterestFrequency;
import com.banklab.fdservice.dto.InterestType;

/**
 * Unit tests for the pure interest formula (no Spring, no DB). Expected amounts were
 * computed independently (Python decimal, HALF_UP) — see TEST_REPORT.md.
 */
class InterestCalculatorTest {

    private final InterestCalculator calculator = new InterestCalculator();

    @Test
    void simpleInterestOverAFullNonLeapYearIsPrincipalTimesRate() {
        InterestCalculation result = calculator.calculate(new BigDecimal("100000.00"),
                simple("6.5", null), date("2026-01-01"), date("2027-01-01"));

        assertThat(result.days()).isEqualTo(365);
        assertThat(result.totalInterest()).isEqualByComparingTo("6500.00");
        assertThat(result.maturityValue()).isEqualByComparingTo("106500.00");
        assertThat(result.interestPaidOut()).isEqualByComparingTo("0");
    }

    @Test
    void labManualSimpleExampleOneLakhAtSevenPercentForTwelveMonthsMaturesAt107000() {
        InterestCalculation result = calculator.calculate(new BigDecimal("100000"),
                simple("7.0", null), date("2025-01-01"), date("2026-01-01"));

        assertThat(result.totalInterest()).isEqualByComparingTo("7000.00");
        assertThat(result.maturityValue()).isEqualByComparingTo("107000.00");
    }

    @Test
    void compoundQuarterlyOverTwelveMonthsCapitalizesFourTimesOnActualDays() {
        // Quarters of 90, 91, 92, 92 days: 1602.74 + 1646.52 + 1691.59 + 1719.30
        InterestCalculation result = calculator.calculate(new BigDecimal("100000.00"),
                compound("6.5", InterestFrequency.QUARTERLY), date("2026-01-01"), date("2027-01-01"));

        assertThat(result.totalInterest()).isEqualByComparingTo("6660.15");
        assertThat(result.maturityValue()).isEqualByComparingTo("106660.15");

        // Lab manual formula P(1 + r/n)^n = 106660.46 — day-based sub-periods stay within a rupee of it.
        BigDecimal manualFormula = new BigDecimal("100000").multiply(new BigDecimal("1.01625").pow(4));
        assertThat(result.maturityValue()).isCloseTo(manualFormula, within(new BigDecimal("1.00")));
    }

    @Test
    void compoundMonthlyFromTheThirtyFirstDoesNotDriftAfterFebruary() {
        // Boundaries: Feb 28, Mar 31, Apr 30, May 31, Jun 30, Jul 31 — anchored on the value date.
        InterestCalculation result = calculator.calculate(new BigDecimal("50000.00"),
                compound("7.25", InterestFrequency.MONTHLY), date("2026-01-31"), date("2026-07-31"));

        assertThat(result.totalInterest()).isEqualByComparingTo("1824.74");
        assertThat(result.maturityValue()).isEqualByComparingTo("51824.74");
    }

    @Test
    void act365FixedDividesALeapFebruaryBy365() {
        InterestCalculation result = calculator.calculate(new BigDecimal("100000.00"),
                simple("6.5", null), date("2028-02-01"), date("2028-03-01"));

        assertThat(result.days()).isEqualTo(29);
        // 100000 × 6.5% × 29/365 = 516.44 — the convention older accounts keep
        assertThat(result.totalInterest()).isEqualByComparingTo("516.44");
    }

    @Test
    void actActDividesLeapYearDaysBy366() {
        InterestCalculation result = calculator.calculate(new BigDecimal("100000.00"),
                terms("6.5", InterestType.SIMPLE, null, DayCountConvention.ACT_ACT), date("2028-02-01"),
                date("2028-03-01"));

        // 100000 × 6.5% × 29/366 = 515.03
        assertThat(result.totalInterest()).isEqualByComparingTo("515.03");
    }

    @Test
    void actActSplitsAPeriodThatRunsIntoALeapYear() {
        // 1 Dec 2027 → 1 Feb 2028: 31 days / 365 + 31 days / 366
        assertThat(calculator.simpleInterest(new BigDecimal("100000.00"),
                terms("6.5", InterestType.SIMPLE, null, DayCountConvention.ACT_ACT), date("2027-12-01"),
                date("2028-02-01"))).isEqualByComparingTo("1102.6012");
    }

    @Test
    void actActEqualsAct365OutsideLeapYearsAndGivesAFullRateOverAWholeLeapYear() {
        InterestTerms actAct = terms("6.5", InterestType.SIMPLE, null, DayCountConvention.ACT_ACT);
        assertThat(calculator.calculate(new BigDecimal("100000.00"), actAct, date("2026-01-01"), date("2027-01-01"))
                .totalInterest()).isEqualByComparingTo("6500.00");
        // 366 days / 366 — exactly one year's interest, not 366/365 of it
        assertThat(calculator.calculate(new BigDecimal("100000.00"), actAct, date("2028-01-01"), date("2029-01-01"))
                .totalInterest()).isEqualByComparingTo("6500.00");
    }

    @Test
    void actActCompoundQuarterlyThroughALeapYear() {
        // Quarters of 91, 91, 92, 92 leap-year days: 1616.12 + 1642.24 + 1687.12 + 1714.68
        InterestCalculation result = calculator.calculate(new BigDecimal("100000.00"),
                terms("6.5", InterestType.COMPOUND, InterestFrequency.QUARTERLY, DayCountConvention.ACT_ACT),
                date("2028-01-01"), date("2029-01-01"));

        assertThat(result.totalInterest()).isEqualByComparingTo("6660.16");
        assertThat(result.maturityValue()).isEqualByComparingTo("106660.16");
    }

    @Test
    void zeroLengthPeriodEarnsNothing() {
        InterestCalculation result = calculator.calculate(new BigDecimal("100000.00"),
                compound("6.5", InterestFrequency.QUARTERLY), date("2026-03-15"), date("2026-03-15"));

        assertThat(result.days()).isZero();
        assertThat(result.totalInterest()).isEqualByComparingTo("0");
        assertThat(result.maturityValue()).isEqualByComparingTo("100000.00");
    }

    @Test
    void singleDayPeriodEarnsOneDayOfInterest() {
        assertThat(calculator.simpleInterest(new BigDecimal("100000.00"), simple("6.5", null),
                date("2026-01-01"), date("2026-01-02"))).isEqualByComparingTo("17.8082");
        assertThat(calculator.calculate(new BigDecimal("100000.00"), simple("6.5", null),
                date("2026-01-01"), date("2026-01-02")).totalInterest()).isEqualByComparingTo("17.81");
    }

    @Test
    void zeroRateReturnsZeroInterestRatherThanAnError() {
        InterestCalculation result = calculator.calculate(new BigDecimal("250000.00"),
                compound("0", InterestFrequency.QUARTERLY), date("2026-01-01"), date("2027-01-01"));

        assertThat(result.totalInterest()).isEqualByComparingTo("0");
        assertThat(result.maturityValue()).isEqualByComparingTo("250000.00");
    }

    @Test
    void simpleInterestWithMonthlyPayoutPaysInterestOutAndMaturesAtPrincipal() {
        // Jan 31d 662.47, Feb 28d 598.36, Mar 31d 662.47 — each rounded as it is paid.
        InterestCalculation result = calculator.calculate(new BigDecimal("120000.00"),
                simple("6.5", InterestFrequency.MONTHLY), date("2026-01-01"), date("2026-04-01"));

        assertThat(result.totalInterest()).isEqualByComparingTo("1923.30");
        assertThat(result.interestPaidOut()).isEqualByComparingTo("1923.30");
        assertThat(result.maturityValue()).isEqualByComparingTo("120000.00");
    }

    @Test
    void zeroDecimalCurrencyRoundsToWholeUnits() {
        InterestTerms yenTerms = new InterestTerms(new BigDecimal("1.5"), InterestType.SIMPLE, null, null,
                DayCountConvention.ACT_365, 0);

        InterestCalculation result = calculator.calculate(new BigDecimal("1000000"), yenTerms,
                date("2026-01-01"), date("2026-04-11"));

        assertThat(result.totalInterest()).isEqualByComparingTo("4110");
        assertThat(result.totalInterest().scale()).isZero();
    }

    @Test
    void periodEndingBeforeItStartsIsRejected() {
        assertThatThrownBy(() -> calculator.calculate(new BigDecimal("1000"), simple("6.5", null),
                date("2026-02-01"), date("2026-01-01")))
                .isInstanceOf(InvalidPeriodException.class);
    }

    @Test
    void negativePrincipalIsRejected() {
        assertThatThrownBy(() -> calculator.calculate(new BigDecimal("-1"), simple("6.5", null),
                date("2026-01-01"), date("2026-02-01")))
                .isInstanceOf(InvalidInterestTermsException.class);
    }

    @Test
    void compoundTermsWithoutAPeriodicCompoundingFrequencyAreInvalid() {
        assertThatThrownBy(() -> compound("6.5", InterestFrequency.ON_MATURITY))
                .isInstanceOf(InvalidInterestTermsException.class)
                .hasMessageContaining("compounding frequency");
        assertThatThrownBy(() -> compound("6.5", null))
                .isInstanceOf(InvalidInterestTermsException.class);
    }

    @Test
    void missingOrNegativeRateIsInvalid() {
        assertThatThrownBy(() -> simple(null, null))
                .isInstanceOf(InvalidInterestTermsException.class)
                .hasMessageContaining("FDA_INT_RT");
        assertThatThrownBy(() -> simple("-0.5", null))
                .isInstanceOf(InvalidInterestTermsException.class);
    }

    @Test
    void dayCountConventionParsesSupportedCodesAndRejectsAnythingElse() {
        assertThat(DayCountConvention.fromCode("ACT/365")).isEqualTo(DayCountConvention.ACT_365);
        assertThat(DayCountConvention.fromCode(" act/365 ")).isEqualTo(DayCountConvention.ACT_365);
        assertThat(DayCountConvention.fromCode("ACT/ACT")).isEqualTo(DayCountConvention.ACT_ACT);
        assertThat(DayCountConvention.fromCode("act_act")).isEqualTo(DayCountConvention.ACT_ACT);
        assertThatThrownBy(() -> DayCountConvention.fromCode("30/360"))
                .isInstanceOf(InvalidInterestTermsException.class);
        assertThatThrownBy(() -> DayCountConvention.fromCode(null))
                .isInstanceOf(InvalidInterestTermsException.class);
    }

    @Test
    void interestFrequencyParsesStoredValuesAndAliases() {
        assertThat(InterestFrequency.parse("QUARTERLY")).isEqualTo(InterestFrequency.QUARTERLY);
        assertThat(InterestFrequency.parse("half-yearly")).isEqualTo(InterestFrequency.HALF_YEARLY);
        assertThat(InterestFrequency.parse("YEARLY")).isEqualTo(InterestFrequency.ANNUALLY);
        assertThat(InterestFrequency.parse("MATURITY")).isEqualTo(InterestFrequency.ON_MATURITY);
        assertThat(InterestFrequency.parse("  ")).isNull();
        assertThatThrownBy(() -> InterestFrequency.parse("WEEKLY"))
                .isInstanceOf(InvalidInterestTermsException.class);
    }

    @Test
    void currencyDecimalsComeFromIso4217() {
        assertThat(CurrencyDecimals.forCurrency("INR")).isEqualTo(2);
        assertThat(CurrencyDecimals.forCurrency("JPY")).isZero();
        assertThat(CurrencyDecimals.forCurrency("KWD")).isEqualTo(3);
        assertThatThrownBy(() -> CurrencyDecimals.forCurrency("NOPE")).isInstanceOf(InvalidPrincipalException.class);
        assertThat(CurrencyDecimals.forCurrency("BHD")).isEqualTo(3);
    }

    static InterestTerms terms(String rate, InterestType type, InterestFrequency frequency,
            DayCountConvention convention) {
        return type == InterestType.COMPOUND
                ? new InterestTerms(new BigDecimal(rate), type, frequency, InterestFrequency.ON_MATURITY, convention, 2)
                : new InterestTerms(new BigDecimal(rate), type, null, frequency, convention, 2);
    }

    static InterestTerms simple(String rate, InterestFrequency payoutFrequency) {
        return new InterestTerms(rate == null ? null : new BigDecimal(rate), InterestType.SIMPLE, null,
                payoutFrequency, DayCountConvention.ACT_365, 2);
    }

    static InterestTerms compound(String rate, InterestFrequency compoundingFrequency) {
        return new InterestTerms(new BigDecimal(rate), InterestType.COMPOUND, compoundingFrequency,
                InterestFrequency.ON_MATURITY, DayCountConvention.ACT_365, 2);
    }

    static LocalDate date(String iso) {
        return LocalDate.parse(iso);
    }
}
