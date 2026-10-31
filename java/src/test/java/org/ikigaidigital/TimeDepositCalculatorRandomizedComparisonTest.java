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
    // Balances whose interest is an exact half-cent tie (0.125) or a near-tie for some plan.
    private static final double[] BOUNDARY_BALANCES = {0.0, 0.01, 3.60, 6.00, 10.00, 18.00, 30.00, 50.00, 90.00, 150.00, -150.00};
    // Whole cents below 10^13 (ADR-0006's column bound) are 10^15 cents.
    private static final int MAX_CENT_DIGITS = 15;

    @Test
    void matchesFrozenLegacyCopyOnGeneratedDeposits() {
        Random random = new Random(SEED);
        List<TimeDeposit> actual = new ArrayList<>();
        List<TimeDeposit> expected = new ArrayList<>();
        double[] inputBalances = new double[DEPOSITS];
        for (int i = 0; i < DEPOSITS; i++) {
            String planType = PLAN_TYPES[random.nextInt(PLAN_TYPES.length)];
            int days = random.nextBoolean()
                    ? BOUNDARY_DAYS[random.nextInt(BOUNDARY_DAYS.length)]
                    : random.nextInt(1_000);
            double balance = randomBalance(random);
            inputBalances[i] = balance;
            actual.add(new TimeDeposit(i + 1, planType, balance, days));
            expected.add(new TimeDeposit(i + 1, planType, balance, days));
        }

        new TimeDepositCalculator().updateBalance(actual);
        new FrozenLegacyTimeDepositCalculator().updateBalance(expected);

        List<String> mismatches = new ArrayList<>();
        for (int i = 0; i < DEPOSITS; i++) {
            TimeDeposit a = actual.get(i);
            TimeDeposit e = expected.get(i);
            if (!a.getBalance().equals(e.getBalance())) {
                mismatches.add("deposit %d (%s, %d days, balance %s): expected %s but was %s".formatted(
                        a.getId(), a.getPlanType(), a.getDays(), inputBalances[i], e.getBalance(), a.getBalance()));
            }
        }
        assertThat(mismatches).as("mismatches against the frozen legacy copy (seed %d)", SEED).isEmpty();
    }

    // A quarter boundary balances; otherwise whole cents with a log-uniform magnitude from 1 cent to below 10^13,
    // so small, realistic and very large balances are all frequent.
    private static double randomBalance(Random random) {
        if (random.nextInt(4) == 0) {
            return BOUNDARY_BALANCES[random.nextInt(BOUNDARY_BALANCES.length)];
        }
        long bound = (long) Math.pow(10, 1 + random.nextInt(MAX_CENT_DIGITS));
        return random.nextLong(bound) / 100.0;
    }
}
