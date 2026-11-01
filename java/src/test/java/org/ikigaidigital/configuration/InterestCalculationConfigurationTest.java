package org.ikigaidigital.configuration;

import org.ikigaidigital.TimeDeposit;
import org.ikigaidigital.TimeDepositCalculator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The calculator bean is wired from the default interest policies (ADR-0011, E1). An ApplicationContextRunner loads
 * only this configuration, so the test needs no database; one basic deposit shows that today's plans are used.
 */
class InterestCalculationConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(InterestCalculationConfiguration.class);

    @Test
    void providesTheCalculatorWithTodaysPlans() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(TimeDepositCalculator.class);

            TimeDeposit deposit = new TimeDeposit(1, "basic", 1200.00, 31);
            context.getBean(TimeDepositCalculator.class).updateBalance(List.of(deposit));

            assertThat(deposit.getBalance()).isEqualTo(1201.00);
        });
    }
}
