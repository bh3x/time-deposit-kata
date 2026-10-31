package org.ikigaidigital.shared.application;

import org.springframework.stereotype.Service;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks an application service that implements a driving port ({@code port.in}). A Spring stereotype like
 * {@code @RestController}: picked up by default component scanning (ADR-0008, decision 6). Adapted from buckpal's
 * {@code @UseCase}, but without {@code @AliasFor}, so it uses nothing from Spring beyond the stereotype.
 */
@Documented
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Service
public @interface UseCase {
}
