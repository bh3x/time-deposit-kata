package org.ikigaidigital.timedeposit.application.service;

import org.ikigaidigital.timedeposit.application.model.TimeDepositView;
import org.ikigaidigital.timedeposit.application.port.in.ListTimeDepositsUseCase;
import org.ikigaidigital.timedeposit.application.port.out.fake.InMemoryLoadTimeDepositViewsPort;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.ikigaidigital.timedeposit.TimeDepositFixtures.aDeposit;
import static org.ikigaidigital.timedeposit.TimeDepositFixtures.aView;
import static org.ikigaidigital.timedeposit.TimeDepositFixtures.aWithdrawal;

class ListTimeDepositsServiceTest {

    private final InMemoryLoadTimeDepositViewsPort deposits = new InMemoryLoadTimeDepositViewsPort();
    private final ListTimeDepositsUseCase listTimeDeposits = new ListTimeDepositsService(deposits);

    @Test
    void returnsNoDepositsWhenNoneExist() {
        assertThat(listTimeDeposits.listAll()).isNotNull().isEmpty();
    }

    @Test
    void returnsEveryDepositFromThePortUnchanged() {
        TimeDepositView basic = aView(1, aDeposit("basic", 1000.00, 31),
                aWithdrawal(10, "100.00", "2026-08-01"), aWithdrawal(11, "50.00", "2026-09-15"));
        TimeDepositView student = aView(2, aDeposit("student", 500.00, 100));
        TimeDepositView unknownPlan = aView(3, aDeposit("gold", 1000.9300000000001, 400));
        deposits.replaceWith(basic, student, unknownPlan);

        // Same instances: the use case neither maps, rounds nor drops anything.
        assertThat(listTimeDeposits.listAll())
                .hasSize(3)
                .anySatisfy(view -> assertThat(view).isSameAs(basic))
                .anySatisfy(view -> assertThat(view).isSameAs(student))
                .anySatisfy(view -> assertThat(view).isSameAs(unknownPlan));
    }

    // The port defines the order (ADR-0008); the use case must keep it.
    @Test
    void keepsTheOrderGivenByThePort() {
        TimeDepositView c = aView(30, aDeposit("basic", 1.00, 31));
        TimeDepositView a = aView(10, aDeposit("basic", 2.00, 31));
        TimeDepositView b = aView(20, aDeposit("basic", 3.00, 31));
        deposits.replaceWith(c, a, b);

        assertThat(listTimeDeposits.listAll()).containsExactly(c, a, b);
    }

    @Test
    void eachCallReadsTheCurrentDeposits() {
        TimeDepositView before = aView(1, aDeposit("basic", 1000.00, 31));
        TimeDepositView after = aView(2, aDeposit("premium", 2000.00, 46));
        deposits.replaceWith(before);
        listTimeDeposits.listAll();

        deposits.replaceWith(after);

        assertThat(listTimeDeposits.listAll()).containsExactly(after);
    }

    @Test
    void rejectsAMissingPort() {
        assertThatNullPointerException().isThrownBy(() -> new ListTimeDepositsService(null));
    }

    @Test
    void propagatesAFailureOfThePort() {
        IllegalStateException failure = new IllegalStateException("boom");
        deposits.failWith(failure);

        assertThatThrownBy(listTimeDeposits::listAll).isSameAs(failure);
    }
}
