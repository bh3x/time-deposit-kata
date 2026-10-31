package org.ikigaidigital.testcontainers;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The one PostgreSQL container for all integration tests (ADR-0009): a Spring bean, so it starts once per cached
 * application context, and a {@link ServiceConnection}, so the datasource points at it. The image tag is pinned and
 * must match Docker Compose.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresContainerConfiguration {

    public static final String POSTGRES_IMAGE = "postgres:18.6";

    @Bean
    @ServiceConnection
    PostgreSQLContainer postgresContainer() {
        return new PostgreSQLContainer(POSTGRES_IMAGE);
    }
}
