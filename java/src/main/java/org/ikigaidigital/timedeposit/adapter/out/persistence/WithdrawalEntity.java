package org.ikigaidigital.timedeposit.adapter.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/** JPA mapping of {@code "withdrawals"} (ADR-0006); read together with its deposit, never exposed. */
@Entity
@Table(name = "\"withdrawals\"")
class WithdrawalEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "\"id\"")
    private Integer id;

    @Column(name = "\"publicId\"", nullable = false, insertable = false, updatable = false)
    private UUID publicId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "\"timeDepositId\"", nullable = false)
    private TimeDepositEntity timeDeposit;

    @Column(name = "\"amount\"", nullable = false, precision = 15, scale = 2)
    private BigDecimal amount;

    @Column(name = "\"date\"", nullable = false)
    private LocalDate date;

    protected WithdrawalEntity() {
        // for JPA
    }

    UUID getPublicId() {
        return publicId;
    }

    BigDecimal getAmount() {
        return amount;
    }

    LocalDate getDate() {
        return date;
    }
}
