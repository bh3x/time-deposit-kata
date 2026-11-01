package org.ikigaidigital.configuration;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

/** Misconfigured origins fail at startup rather than opening or silently closing the API (ADR-0010). */
class ApiCorsPropertiesTest {

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

    // A trailing comma in APP_CORS_ALLOWEDORIGINS binds an empty entry, which would silently allow nothing.
    @Test
    void rejectsABlankOrigin() {
        assertThatIllegalArgumentException()
                .isThrownBy(() -> new ApiCorsProperties(List.of("http://localhost:8081", "")));
        assertThatIllegalArgumentException().isThrownBy(() -> new ApiCorsProperties(List.of(" ")));
    }

    @Test
    void keepsTheConfiguredOrigins() {
        assertThat(new ApiCorsProperties(List.of("http://a.test", "http://b.test")).allowedOrigins())
                .containsExactly("http://a.test", "http://b.test");
    }
}
