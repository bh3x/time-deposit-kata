package org.ikigaidigital.configuration;

import org.ikigaidigital.TimeDepositCalculator;
import org.ikigaidigital.timedeposit.domain.interest.InterestPolicies;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Builds the calculator for the balance update use case (ADR-0011, E1). The calculator and the policies carry no
 * Spring annotation, so the domain stays framework-free (ADR-0008); the list of plans stays readable in one place,
 * {@link InterestPolicies#defaults()}, rather than spread over bean definitions. The registry is passed explicitly
 * instead of using the no-argument constructor, so that this is the one place to change when plans come from
 * elsewhere (ADR-0011, A2 deferred).
 */
@Configuration
public class InterestCalculationConfiguration {

    @Bean
    TimeDepositCalculator timeDepositCalculator() {
        return new TimeDepositCalculator(InterestPolicies.defaults());
    }
}
