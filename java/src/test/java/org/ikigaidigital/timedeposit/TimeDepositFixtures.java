package org.ikigaidigital.timedeposit;

import org.ikigaidigital.TimeDeposit;
import org.ikigaidigital.timedeposit.application.model.TimeDepositView;
import org.ikigaidigital.timedeposit.application.model.WithdrawalView;
import org.ikigaidigital.timedeposit.domain.ReadOnlyTimeDeposit;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Test data for the timedeposit capability (ADR-0009): deterministic ids and dates, and only the fields a test
 * cares about.
 */
public final class TimeDepositFixtures {

    private TimeDepositFixtures() {
    }

    /** A fixed, valid version-4 UUID per number, so tests never depend on random ids. */
    public static UUID uuid(int number) {
        return UUID.fromString("00000000-0000-4000-8000-%012d".formatted(number));
    }

    public static TimeDeposit aDeposit(String planType, double balance, int days) {
        return new TimeDeposit(1, planType, balance, days);
    }

    public static WithdrawalView aWithdrawal(int number, String amount, String date) {
        return new WithdrawalView(uuid(number), new BigDecimal(amount), LocalDate.parse(date));
    }

    public static TimeDepositView aView(int number, TimeDeposit deposit, WithdrawalView... withdrawals) {
        return new TimeDepositView(uuid(number), new ReadOnlyTimeDeposit(deposit), List.of(withdrawals));
    }
}
