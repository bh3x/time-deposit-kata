package org.ikigaidigital.configuration;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Lets the Swagger UI container, which runs on its own origin, call the API from the browser (ADR-0010). One global
 * mapping for {@code /api/**} instead of {@code @CrossOrigin} on controllers, so future operations are covered and the
 * policy lives in one place. It adds no route: preflight requests are answered by the framework for existing
 * mappings (ADR-0005).
 * <p>
 * Methods, headers and credentials are listed explicitly rather than taken from Spring's defaults: {@code GET} and
 * {@code HEAD} for the list, {@code POST} for the balance update; the contract defines no request header since
 * ADR-0013, and {@code Content-Type} stays allowed because browser clients may send it on a POST. No credentials: the
 * API has no authentication ({@code security: []}). A request from another origin is rejected by Spring with a
 * plain-text {@code 403}; assumption: like container-level errors, this lies outside the contract (ADR-0010).
 */
@Configuration
@EnableConfigurationProperties(ApiCorsProperties.class)
public class ApiCorsConfiguration implements WebMvcConfigurer {

    private final ApiCorsProperties properties;

    public ApiCorsConfiguration(ApiCorsProperties properties) {
        this.properties = properties;
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        registry.addMapping("/api/**")
                .allowedOrigins(properties.allowedOrigins().toArray(String[]::new))
                .allowedMethods("GET", "HEAD", "POST")
                .allowedHeaders(HttpHeaders.CONTENT_TYPE)
                .allowCredentials(false);
    }
}
