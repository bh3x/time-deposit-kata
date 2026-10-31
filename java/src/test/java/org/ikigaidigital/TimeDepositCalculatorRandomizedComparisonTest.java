package org.ikigaidigital;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Safety net for refactoring: the calculator must produce exactly the same balances as the frozen legacy copy on a
 * large, reproducible set of generated deposits. The seed is fixed, so a failure is always reproducible.
 */
class TimeDepositCalculatorRandomizedComparisonTest {

    private static final long SEED = 20261031L;
    private static final int DEPOSITS = 10_000;
    private static final String[] PLAN_TYPES = {"basic", "student", "premium", "gold", "", "Basic", "PREMIUM", " student"};
    private static final int[] BOUNDARY_DAYS = {-1, 0, 29, 30, 31, 32, 44, 45, 46, 47, 364, 365, 366, 367};
    // Whole cents below 10^13 (ADR-0006's column bound): 10^15 cents.
    private static final long MAX_CENTS = 1_000_000_000_000_000L;

    @Test
    void matchesFrozenLegacyCopyOnGeneratedDeposits() {
        Random random = new Random(SEED);
        List<TimeDeposit> actual = new ArrayList<>();
        List<TimeDeposit> expected = new ArrayList<>();
        for (int id = 1; id <= DEPOSITS; id++) {
            String planType = PLAN_TYPES[random.nextInt(PLAN_TYPES.length)];
            int days = random.nextBoolean()
                    ? BOUNDARY_DAYS[random.nextInt(BOUNDARY_DAYS.length)]
                    : random.nextInt(1_000);
            double balance = randomBalance(random);
            actual.add(new TimeDeposit(id, planType, balance, days));
            expected.add(new TimeDeposit(id, planType, balance, days));
        }

        new TimeDepositCalculator().updateBalance(actual);
        new FrozenLegacyTimeDepositCalculator().updateBalance(expected);

        for (int i = 0; i < DEPOSITS; i++) {
            TimeDeposit a = actual.get(i);
            TimeDeposit e = expected.get(i);
            assertThat(a.getBalance())
                    .as("seed %d, deposit %d (%s, %d days)", SEED, a.getId(), a.getPlanType(), a.getDays())
                    .isEqualTo(e.getBalance());
        }
    }

    // Mostly realistic balances, plus small ones that hit the half-cent rounding edges more often.
    private static double randomBalance(Random random) {
        long cents = random.nextInt(4) == 0
                ? random.nextLong(10_000)
                : random.nextLong(MAX_CENTS);
        return cents / 100.0;
    }
}
