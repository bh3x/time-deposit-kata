package org.ikigaidigital.configuration;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * The clock of the balance update (ADR-0012): a bean rather than {@code Instant.now()} in the services, so tests fix
 * time and check the 10-minute and 24-hour boundaries at exact instants. UTC, the zone of {@code executedAt} in the
 * contract.
 */
@Configuration
public class ClockConfiguration {

    @Bean
    Clock clock() {
        return Clock.systemUTC();
    }
}
