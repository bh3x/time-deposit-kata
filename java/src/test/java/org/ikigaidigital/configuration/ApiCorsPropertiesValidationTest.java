package org.ikigaidigital.configuration;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/** Misconfigured origins fail at startup rather than opening or silently closing the API (ADR-0010). */
class ApiCorsPropertiesValidationTest {

    @Test
    void rejectsAWildcardOrigin() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ApiCorsProperties(List.of("http://localhost:8081", "*")));
    }

    @Test
    void rejectsAMissingOrEmptyList() {
        assertThatIllegalArgumentException().isThrownBy(() -> new ApiCorsProperties(null));
        assertThatIllegalArgumentException().isThrownBy(() -> new ApiCorsProperties(List.of()));
    }

    @Test
    void keepsTheConfiguredOrigins() {
        assertThat(new ApiCorsProperties(List.of("http://a.test", "http://b.test")).allowedOrigins())
                .containsExactly("http://a.test", "http://b.test");
    }
}
