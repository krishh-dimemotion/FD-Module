package com.banklab.fdservice.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

/**
 * The one place FD interest is computed. Side-effect free — no repositories, no
 * clock — so the interest preview endpoint, the booking-time maturity quote, the
 * accrual/maturity batch and premature closure all share the exact same arithmetic.
 *
 * <h2>Formula</h2>
 * Interest for a run of days [from, to) is always simple interest on the current base,
 * using the account's day-count convention — the deposit day counts, the day money
 * leaves doesn't:
 * <pre>  interest = base × rate/100 × year fraction
 *   ACT/ACT: days in common years / 365 + days in leap years / 366
 *   ACT/365: days / 365</pre>
 * For COMPOUND terms the period is cut at compounding boundaries (value date + k ×
 * frequency months, e.g. quarterly: +3, +6, +9 … months). At each boundary the
 * interest for that sub-period is rounded to currency decimals and added to the base
 * before the next sub-period starts. Days after the last boundary earn simple interest
 * on the compounded base. Over whole periods this is the lab manual's
 * P × (1 + r/n)^(nt), except each sub-period uses its actual day count instead of a
 * flat r/n — which is what lets daily accrual in the batch reproduce the quoted amount
 * exactly.
 *
 * SIMPLE terms with a periodic payout frequency are cut the same way at payout
 * boundaries, but the base never grows — the interest is paid out.
 *
 * <h2>Precision</h2>
 * Money is BigDecimal only (ERD P3). Running accrual keeps 4 decimals
 * (FDA_ACCRUED_INT_AMT is DECIMAL(18,4)); anything actually posted is rounded HALF_UP
 * to the account's currency decimals.
 */
@Component
public class InterestCalculator {

    /** Scale of FDA_ACCRUED_INT_AMT. */
    public static final int ACCRUAL_SCALE = 4;

    /**
     * Interest over [periodStart, periodEnd) for a principal under the given terms —
     * used by the preview endpoint and by the maturity quote at booking. Compounding /
     * payout boundaries are anchored on {@code periodStart}.
     */
    public InterestCalculation calculate(BigDecimal principal, InterestTerms terms, LocalDate periodStart,
            LocalDate periodEnd) {
        return calculate(principal,terms,periodStart,periodEnd,(gross,date)->BigDecimal.ZERO);
    }

    public InterestCalculation calculate(BigDecimal principal, InterestTerms terms, LocalDate periodStart,
            LocalDate periodEnd, java.util.function.BiFunction<BigDecimal,LocalDate,BigDecimal> tax) {
        requireNonNegative(principal);
        requirePeriod(periodStart, periodEnd);

        InterestProjection projection = project(principal, terms, periodStart, periodStart, periodEnd, tax);
        BigDecimal stubInterest = terms.roundToCurrency(projection.accruedInterest());

        BigDecimal stubTax = stubInterest.signum()>0 ? tax.apply(stubInterest,periodEnd) : zero(terms);
        BigDecimal withheld = stubTax;
        BigDecimal settled = zero(terms);
        BigDecimal paidOut = zero(terms);
        for (InterestEvent event : projection.events()) {
            settled = settled.add(event.amount());
            withheld = withheld.add(event.taxAmount());
            if (event.type() == InterestEventType.PAYOUT) {
                paidOut = paidOut.add(event.amount().subtract(event.taxAmount()));
            }
        }

        // Rounded so a zero-decimal currency (JPY) doesn't inherit the principal's DECIMAL(18,2) scale.
        return new InterestCalculation(
                principal,
                terms.roundToCurrency(settled.add(stubInterest)),
                terms.roundToCurrency(paidOut),
                terms.roundToCurrency(projection.closingBalance().add(stubInterest).subtract(stubTax)),
                ChronoUnit.DAYS.between(periodStart, periodEnd), terms.roundToCurrency(withheld));
    }

    /**
     * The exclusive accrual end for the end-of-day run of {@code businessDate}. Standard
     * practice: the EOD that closes a day accrues that day's interest, so a deposit
     * made on 13 Sep has 13 Sep's interest accrued by the run at 00:00 on 14 Sep.
     * Never past maturity — the maturity date itself earns nothing.
     */
    public static LocalDate endOfDayAccrualTarget(LocalDate businessDate, LocalDate maturityDate) {
        LocalDate dayAfter = businessDate.plusDays(1);
        return dayAfter.isAfter(maturityDate) ? maturityDate : dayAfter;
    }

    /**
     * Plans accrual for one account for the days before {@code accrueThrough} (exclusive,
     * capped at maturity). The plan is recomputed from the start of the current
     * settlement period each time rather than adding one day's interest, so running it
     * twice for the same date — or catching up several missed days at once — gives the
     * same result.
     */
    public AccrualPlan planAccrual(AccountInterestState state, InterestTerms terms, LocalDate accrueThrough) {
        return planAccrual(state,terms,accrueThrough,(gross,date)->BigDecimal.ZERO);
    }

    public AccrualPlan planAccrual(AccountInterestState state, InterestTerms terms, LocalDate accrueThrough,
            java.util.function.BiFunction<BigDecimal,LocalDate,BigDecimal> tax) {
        LocalDate target = accrueThrough.isAfter(state.maturityDate()) ? state.maturityDate() : accrueThrough;
        LocalDate alreadyAccruedThrough = state.accruedThrough();
        if (alreadyAccruedThrough != null && !alreadyAccruedThrough.isBefore(target)) {
            return AccrualPlan.noOp(state);
        }
        accrueThrough = target;

        LocalDate periodStart = state.lastSettlementDate() != null ? state.lastSettlementDate() : state.valueDate();
        if (!accrueThrough.isAfter(periodStart)) {
            return AccrualPlan.noOp(state);
        }

        InterestProjection projection = project(state.principalBalance(), terms, state.valueDate(), periodStart,
                accrueThrough, tax);
        LocalDate lastSettlement = projection.events().isEmpty()
                ? state.lastSettlementDate()
                : projection.lastBoundary();
        return new AccrualPlan(false, projection.events(), projection.closingBalance(),
                projection.accruedInterest(), accrueThrough, lastSettlement);
    }

    /**
     * Simple interest on {@code base} for the days in [from, to), at accrual scale.
     */
    public BigDecimal simpleInterest(BigDecimal base, InterestTerms terms, LocalDate from, LocalDate to) {
        requirePeriod(from, to);
        DayCountConvention convention = terms.dayCountConvention();
        // One division at the end, so no precision is lost to an intermediate rate/365.
        return base.multiply(terms.annualRatePct())
                .multiply(BigDecimal.valueOf(convention.yearFractionNumerator(from, to)))
                .divide(BigDecimal.valueOf(100L * convention.yearFractionDenominator()), ACCRUAL_SCALE,
                        RoundingMode.HALF_UP);
    }

    /**
     * Walks settlement boundaries (anchor + k × frequency months) that fall in
     * (periodStart, end], settling interest at each, then accrues the remaining days.
     * Boundaries are computed from the anchor, not by repeated plusMonths, so a 31st
     * value date doesn't drift to the 28th after February.
     */
    private InterestProjection project(BigDecimal openingBalance, InterestTerms terms, LocalDate anchor,
            LocalDate periodStart, LocalDate end, java.util.function.BiFunction<BigDecimal,LocalDate,BigDecimal> tax) {
        InterestEventType eventType = terms.settlementEventType();
        List<InterestEvent> events = new ArrayList<>();
        BigDecimal balance = openingBalance;
        LocalDate from = periodStart;

        if (eventType != null) {
            long stepMonths = terms.settlementFrequency().months();
            for (long k = 1; ; k++) {
                LocalDate boundary = anchor.plusMonths(stepMonths * k);
                if (boundary.isAfter(end)) {
                    break;
                }
                if (!boundary.isAfter(from)) {
                    continue;
                }
                BigDecimal amount = terms.roundToCurrency(simpleInterest(balance, terms, from, boundary));
                BigDecimal deduction = amount.signum()>0 ? tax.apply(amount,boundary) : zero(terms);
                events.add(new InterestEvent(boundary, amount, eventType, deduction));
                if (eventType == InterestEventType.CAPITALIZATION) {
                    balance = balance.add(amount).subtract(deduction);
                }
                from = boundary;
            }
        }

        return new InterestProjection(events, balance, simpleInterest(balance, terms, from, end), from);
    }

    private record InterestProjection(List<InterestEvent> events, BigDecimal closingBalance,
            BigDecimal accruedInterest, LocalDate lastBoundary) {
    }

    private static BigDecimal zero(InterestTerms terms) {
        return BigDecimal.ZERO.setScale(terms.currencyDecimals());
    }

    private static void requireNonNegative(BigDecimal principal) {
        if (principal == null || principal.signum() < 0) {
            throw new InvalidInterestTermsException("Principal must be zero or positive, got " + principal);
        }
    }

    private static void requirePeriod(LocalDate start, LocalDate end) {
        if (start == null || end == null) {
            throw new InvalidPeriodException("Period start and end dates are both required");
        }
        if (end.isBefore(start)) {
            throw new InvalidPeriodException("Period end " + end + " is before period start " + start);
        }
    }
}
