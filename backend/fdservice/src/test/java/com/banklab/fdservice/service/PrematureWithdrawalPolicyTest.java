package com.banklab.fdservice.service;

import static com.banklab.fdservice.service.InterestCalculatorTest.date;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

/**
 * Unit tests for premature withdrawal rules (lab manual L15 §4 and §8).
 */
class PrematureWithdrawalPolicyTest {

    private final PrematureWithdrawalPolicy onePercent = new PrematureWithdrawalPolicy(new BigDecimal("1.00"),
            BigDecimal.ZERO);

    @Test
    void penaltyIsTheConfiguredPercentOfInterestEarned() {
        assertThat(onePercent.penalty(new BigDecimal("3525.06"), new BigDecimal("103525.06"), 2))
                .isEqualByComparingTo("35.25");
    }

    @Test
    void flatWithdrawalChargeIsAddedOnTop() {
        PrematureWithdrawalPolicy withFee = new PrematureWithdrawalPolicy(new BigDecimal("1.00"),
                new BigDecimal("200"));

        assertThat(withFee.penalty(new BigDecimal("3525.06"), new BigDecimal("103525.06"), 2))
                .isEqualByComparingTo("235.25");
        assertThat(withFee.penalty(BigDecimal.ZERO, new BigDecimal("50000.00"), 2))
                .isEqualByComparingTo("200.00");
    }

    @Test
    void penaltyNeverExceedsTheBalance() {
        PrematureWithdrawalPolicy harsh = new PrematureWithdrawalPolicy(new BigDecimal("50"), new BigDecimal("500"));

        assertThat(harsh.penalty(new BigDecimal("100.00"), new BigDecimal("300.00"), 2))
                .isEqualByComparingTo("300.00");
    }

    @Test
    void roundsOnceAfterAddingFlatFee() {
        PrematureWithdrawalPolicy policy = new PrematureWithdrawalPolicy(BigDecimal.ONE, new BigDecimal("0.004"));
        assertThat(policy.penalty(new BigDecimal("0.40"), BigDecimal.TEN, 2)).isEqualByComparingTo("0.01");
    }

    @Test
    void roundsToZeroDecimalCurrencyAndIgnoresNegativeInterest() {
        assertThat(onePercent.penalty(new BigDecimal("3552"), new BigDecimal("100000"), 0))
                .isEqualByComparingTo("36");
        assertThat(onePercent.penalty(new BigDecimal("-1"), BigDecimal.TEN, 2)).isZero();
        assertThat(onePercent.penalty(BigDecimal.TEN, BigDecimal.ZERO, 2)).isZero();
    }

    @Test
    void nullSettingsAreRejected() {
        assertThatThrownBy(() -> new PrematureWithdrawalPolicy(null, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PrematureWithdrawalPolicy(BigDecimal.ONE, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void negativeSettingsAreRejected() {
        assertThatThrownBy(() -> new PrematureWithdrawalPolicy(new BigDecimal("-1"), BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new PrematureWithdrawalPolicy(BigDecimal.ONE, new BigDecimal("-5")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void activeAccountBetweenValueDateAndMaturityIsWithdrawable() {
        assertThatCode(() -> onePercent.validateEligibility("ACTIVE", date("2026-01-01"), date("2027-01-01"),
                date("2026-07-16"))).doesNotThrowAnyException();
        assertThatCode(() -> onePercent.validateEligibility("ACTIVE", date("2026-01-01"), date("2027-01-01"),
                date("2026-01-01"))).doesNotThrowAnyException();
    }

    @Test
    void closedAccountIsNotWithdrawable() {
        assertThatThrownBy(() -> onePercent.validateEligibility("CLOSED", date("2026-01-01"), date("2027-01-01"),
                date("2026-07-16")))
                .isInstanceOf(AccountNotWithdrawableException.class)
                .extracting("code").isEqualTo(AccountNotWithdrawableException.ACCOUNT_NOT_ACTIVE);
    }

    @Test
    void withdrawalBeforeTheValueDateIsRejected() {
        assertThatThrownBy(() -> onePercent.validateEligibility("ACTIVE", date("2026-01-10"), date("2027-01-10"),
                date("2026-01-09")))
                .isInstanceOf(AccountNotWithdrawableException.class)
                .extracting("code").isEqualTo(AccountNotWithdrawableException.BEFORE_VALUE_DATE);
    }

    @Test
    void onOrAfterMaturityItIsMaturityProcessingNotPrematureWithdrawal() {
        assertThatThrownBy(() -> onePercent.validateEligibility("ACTIVE", date("2026-01-01"), date("2027-01-01"),
                date("2027-01-01")))
                .isInstanceOf(AccountNotWithdrawableException.class)
                .extracting("code").isEqualTo(AccountNotWithdrawableException.ACCOUNT_MATURED);
    }
}
