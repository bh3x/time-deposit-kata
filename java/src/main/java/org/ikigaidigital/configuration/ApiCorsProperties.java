package org.ikigaidigital.configuration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * Cross-origin settings of the API (ADR-0010): the origins allowed to call it from a browser, e.g. the Swagger UI
 * container. Set in {@code application.yml}; a configured list replaces it. A wildcard or an empty list fails at
 * startup: the first would open the API to every site, the second would silently break Swagger UI.
 */
@ConfigurationProperties(prefix = "app.cors")
public record ApiCorsProperties(List<String> allowedOrigins) {

    public ApiCorsProperties {
        if (allowedOrigins == null || allowedOrigins.isEmpty()) {
            throw new IllegalArgumentException("app.cors.allowed-origins must name at least one origin");
        }
        if (allowedOrigins.contains("*")) {
            throw new IllegalArgumentException("app.cors.allowed-origins must not contain the wildcard '*'");
        }
        allowedOrigins = List.copyOf(allowedOrigins);
    }
}
