package org.ikigaidigital.timedeposit.domain;

/**
 * Read-only view of a time deposit's terms (ADR-0008). It deliberately has no setter and no internal id, so readers
 * such as the REST adapter can neither change a balance nor expose the internal key (ADR-0004).
 */
public interface TimeDepositTerms {

    String getPlanType();

    Double getBalance();

    int getDays();
}
