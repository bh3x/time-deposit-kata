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
            double interest = 0;

            if (deposit.getDays() > GRACE_PERIOD_DAYS) {
                if (deposit.getPlanType().equals("student")) {
                    if (deposit.getDays() < STUDENT_MAX_DAYS_EXCLUSIVE) {
                        interest += deposit.getBalance() * STUDENT_ANNUAL_RATE / MONTHS_PER_YEAR;
                    }
                } else if (deposit.getPlanType().equals("premium")) {
                    if (deposit.getDays() > PREMIUM_MIN_DAYS_EXCLUSIVE) {
                        interest += deposit.getBalance() * PREMIUM_ANNUAL_RATE / MONTHS_PER_YEAR;
                    }
                } else if (deposit.getPlanType().equals("basic")) {
                    interest += deposit.getBalance() * BASIC_ANNUAL_RATE / MONTHS_PER_YEAR;
                }
            }

            double a2d = deposit.getBalance() + (new BigDecimal(interest).setScale(2, RoundingMode.HALF_UP)).doubleValue();
            deposit.setBalance(a2d);
        }
    }
}
