package org.ikigaidigital.timedeposit.domain.interest;

import org.ikigaidigital.timedeposit.domain.ReadOnlyTimeDeposit;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.ikigaidigital.timedeposit.TimeDepositFixtures.aDeposit;

class NoInterestPolicyTest {

    // Raw bits, so that a -0.0 or NaN result would fail (isEqualTo(0.0) accepts -0.0).
    @ParameterizedTest(name = "balance {0} at {1} days earns +0.0")
    @CsvSource({
            "1200.00, -2147483648", "1200.00, 31", "1200.00, 2147483647",
            "-1200.00, 31", "NaN, 31", "Infinity, 31", "-Infinity, 31"})
    void paysNothingWhateverTheTerms(double balance, int days) {
        double interest = NoInterestPolicy.INSTANCE.monthlyInterest(new ReadOnlyTimeDeposit(aDeposit("gold", balance, days)));

        assertThat(Double.doubleToRawLongBits(interest)).isEqualTo(Double.doubleToRawLongBits(0.0));
    }
}
