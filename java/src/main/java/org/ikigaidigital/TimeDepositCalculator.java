package org.ikigaidigital;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;

public class TimeDepositCalculator {
    private static final int GRACE_PERIOD_DAYS = 30;
    // "No interest after 1 year": the legacy rule is days < 366, so day 365 still earns interest.
    private static final int STUDENT_MAX_DAYS_EXCLUSIVE = 366;
    private static final int PREMIUM_MIN_DAYS_EXCLUSIVE = 45;
    private static final double BASIC_ANNUAL_RATE = 0.01;
    private static final double STUDENT_ANNUAL_RATE = 0.03;
    private static final double PREMIUM_ANNUAL_RATE = 0.05;
    private static final int MONTHS_PER_YEAR = 12;

    public void updateBalance(List<TimeDeposit> timeDeposits) {
        for (TimeDeposit deposit : timeDeposits) {
            double interest = monthlyInterest(deposit);
            deposit.setBalance(deposit.getBalance() + roundToCents(interest));
        }
    }

    // One month of interest for the deposit's plan. The plan type is read only after the grace period, and matched
    // exactly and case-sensitively; unknown plans earn nothing.
    private static double monthlyInterest(TimeDeposit deposit) {
        if (deposit.getDays() <= GRACE_PERIOD_DAYS) {
            return 0;
        }
        return switch (deposit.getPlanType()) {
            case "student" -> deposit.getDays() < STUDENT_MAX_DAYS_EXCLUSIVE
                    ? deposit.getBalance() * STUDENT_ANNUAL_RATE / MONTHS_PER_YEAR
                    : 0;
            case "premium" -> deposit.getDays() > PREMIUM_MIN_DAYS_EXCLUSIVE
                    ? deposit.getBalance() * PREMIUM_ANNUAL_RATE / MONTHS_PER_YEAR
                    : 0;
            case "basic" -> deposit.getBalance() * BASIC_ANNUAL_RATE / MONTHS_PER_YEAR;
            default -> 0;
        };
    }

    // Only the interest is rounded, from its exact binary value (new BigDecimal(double)); the sum is not rounded.
    private static double roundToCents(double amount) {
        return new BigDecimal(amount).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }
}
