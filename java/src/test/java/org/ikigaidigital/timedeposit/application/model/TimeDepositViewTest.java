package org.ikigaidigital.timedeposit.application.model;

import org.ikigaidigital.timedeposit.domain.ReadOnlyTimeDeposit;
import org.ikigaidigital.timedeposit.domain.TimeDepositTerms;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.ikigaidigital.timedeposit.TimeDepositFixtures.aDeposit;
import static org.ikigaidigital.timedeposit.TimeDepositFixtures.aWithdrawal;
import static org.ikigaidigital.timedeposit.TimeDepositFixtures.uuid;

class TimeDepositViewTest {

    private final TimeDepositTerms terms = new ReadOnlyTimeDeposit(aDeposit("basic", 1000.00, 31));
    private final WithdrawalView first = aWithdrawal(10, "100.00", "2026-09-15");
    private final WithdrawalView second = aWithdrawal(11, "50.00", "2026-08-01");

    @Test
    void rejectsNullComponents() {
        assertThatNullPointerException().isThrownBy(() -> new TimeDepositView(null, terms, List.of()));
        assertThatNullPointerException().isThrownBy(() -> new TimeDepositView(uuid(1), null, List.of()));
        assertThatNullPointerException().isThrownBy(() -> new TimeDepositView(uuid(1), terms, null));
    }

    @Test
    void rejectsANullWithdrawal() {
        List<WithdrawalView> withNull = Arrays.asList(first, null);

        assertThatNullPointerException().isThrownBy(() -> new TimeDepositView(uuid(1), terms, withNull));
    }

    @Test
    void keepsItsOwnCopyOfTheWithdrawals() {
        List<WithdrawalView> withdrawals = new ArrayList<>(List.of(first));
        TimeDepositView view = new TimeDepositView(uuid(1), terms, withdrawals);

        withdrawals.add(second);

        assertThat(view.withdrawals()).containsExactly(first);
    }

    @Test
    void withdrawalsCannotBeModified() {
        TimeDepositView view = new TimeDepositView(uuid(1), terms, List.of(first));

        assertThatThrownBy(() -> view.withdrawals().add(second)).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void acceptsADepositWithoutWithdrawals() {
        TimeDepositView view = new TimeDepositView(uuid(1), terms, List.of());

        assertThat(view.withdrawals()).isNotNull().isEmpty();
    }

    // Ordering is the persistence adapter's job (ADR-0008); the model must not re-sort.
    @Test
    void keepsTheWithdrawalOrderGivenToIt() {
        TimeDepositView view = new TimeDepositView(uuid(1), terms, List.of(first, second));

        assertThat(view.withdrawals()).containsExactly(first, second);
    }
}
