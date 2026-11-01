package org.ikigaidigital;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.webmvc.autoconfigure.error.ErrorMvcAutoConfiguration;

/**
 * Spring Boot entry point. It lives in the root package so component scanning and entity/repository discovery work
 * as Spring Boot documents them (ADR-0008, decision 9).
 * <p>
 * ADR-0005: the application maps only the two contract operations, so Spring Boot's {@code /error} route
 * ({@code BasicErrorController} from {@link ErrorMvcAutoConfiguration}) is excluded rather than argued away. Errors
 * handled by Spring MVC are Problem Details anyway; errors outside it get the servlet container's default response
 * (accepted in ADR-0005). Excluded here, not with {@code spring.autoconfigure.exclude}: a profile or environment
 * variable setting that property would replace the list and bring {@code /error} back, while an annotation exclusion
 * always applies.
 */
@SpringBootApplication(exclude = ErrorMvcAutoConfiguration.class)
public class TimeDepositApplication {

    public static void main(String[] args) {
        SpringApplication.run(TimeDepositApplication.class, args);
    }
}
