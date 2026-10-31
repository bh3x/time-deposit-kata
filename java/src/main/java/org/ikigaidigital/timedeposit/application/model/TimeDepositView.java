package org.ikigaidigital.timedeposit.application.model;

import org.ikigaidigital.timedeposit.domain.TimeDepositTerms;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A time deposit as listed by {@code GET /time-deposits} (ADR-0008, decision 4): the public UUID, the read-only terms
 * of the legacy deposit and its withdrawals, in the order the persistence adapter returns them. Not a snapshot:
 * {@code terms} delegates to the live legacy object and compares by identity.
 */
public record TimeDepositView(UUID id, TimeDepositTerms terms, List<WithdrawalView> withdrawals) {

    public TimeDepositView {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(terms, "terms");
        // Fails fast on a null list or element (the adapter guarantees an empty list) and keeps an unmodifiable copy.
        withdrawals = List.copyOf(withdrawals);
    }
}
