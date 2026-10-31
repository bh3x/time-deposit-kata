package org.ikigaidigital.timedeposit.domain;

import org.ikigaidigital.TimeDeposit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.ikigaidigital.timedeposit.TimeDepositFixtures.aDeposit;

class ReadOnlyTimeDepositTest {

    @ParameterizedTest(name = "plan type \"{0}\"")
    @ValueSource(strings = {"basic", "student", "premium"})
    void exposesTheTermsOfTheWrappedDeposit(String planType) {
        TimeDepositTerms terms = new ReadOnlyTimeDeposit(aDeposit(planType, 1234.56, 31));

        assertThat(terms.getPlanType()).isEqualTo(planType);
        assertThat(terms.getBalance()).isEqualTo(1234.56);
        assertThat(terms.getDays()).isEqualTo(31);
    }

    // Rounding to cents belongs to the REST adapter (ADR-0004); the read model passes the exact double through.
    @ParameterizedTest(name = "balance {0}")
    @ValueSource(doubles = {1000.9300000000001, 0.0, 9_999_999_999_999.99})
    void doesNotRoundTheBalance(double balance) {
        TimeDepositTerms terms = new ReadOnlyTimeDeposit(aDeposit("basic", balance, 0));

        assertThat(terms.getBalance()).isEqualTo(balance);
        assertThat(terms.getDays()).isZero();
    }

    @Test
    void reflectsALaterBalanceChangeOfTheWrappedDeposit() {
        TimeDeposit deposit = aDeposit("basic", 1000.00, 31);
        TimeDepositTerms terms = new ReadOnlyTimeDeposit(deposit);

        deposit.setBalance(1000.83);

        assertThat(terms.getBalance()).isEqualTo(1000.83);
    }

    @Test
    void rejectsANullDeposit() {
        assertThatNullPointerException().isThrownBy(() -> new ReadOnlyTimeDeposit(null));
    }

    @Test
    void isEqualOnlyToItself() {
        TimeDeposit deposit = aDeposit("premium", 1000.00, 46);
        ReadOnlyTimeDeposit first = new ReadOnlyTimeDeposit(deposit);
        ReadOnlyTimeDeposit secondOfSameDeposit = new ReadOnlyTimeDeposit(deposit);

        assertThat(first).isEqualTo(first).hasSameHashCodeAs(first);
        assertThat(first).isNotEqualTo(secondOfSameDeposit);
        assertThat(first).isNotEqualTo(new ReadOnlyTimeDeposit(aDeposit("premium", 1000.00, 46)));
    }
}
