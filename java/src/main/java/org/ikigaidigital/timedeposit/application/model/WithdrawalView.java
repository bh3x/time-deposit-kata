package org.ikigaidigital.timedeposit.application.model;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Objects;
import java.util.UUID;

/**
 * A withdrawal as listed with its time deposit. {@code id} is the public UUID; the internal keys are never part of a
 * view (ADR-0004). The amount keeps the scale it was read with: formatting is the REST adapter's job, so compare
 * amounts by value, not with {@code equals}.
 */
public record WithdrawalView(UUID id, BigDecimal amount, LocalDate date) {

    public WithdrawalView {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(date, "date");
    }
}
