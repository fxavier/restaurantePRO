package com.xavier.backend.customers.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.UuidGenerator;

/** A change of a credit limit, with its reason and actor (D73, spec §9). A fact; {@code oldLimit} is null when the
 * change opened the account. */
@Entity
@Immutable
@Table(schema = "customers", name = "credit_limit_change")
public class CreditLimitChange {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID customerId;

    @Column(updatable = false, precision = 19, scale = 4)
    private BigDecimal oldLimit;

    @Column(nullable = false, updatable = false, precision = 19, scale = 4)
    private BigDecimal newLimit;

    @Column(nullable = false, updatable = false)
    private String reason;

    @Column(nullable = false, updatable = false, length = 10)
    private String actorKind;

    @Column(nullable = false, updatable = false)
    private UUID actorId;

    @Column(nullable = false, updatable = false)
    private Instant at;

    protected CreditLimitChange() {
        // JPA
    }

    public static CreditLimitChange of(UUID customerId, BigDecimal oldLimit, BigDecimal newLimit, String reason,
                                       String actorKind, UUID actorId, Instant now) {
        CreditLimitChange change = new CreditLimitChange();
        change.customerId = Objects.requireNonNull(customerId, "customerId");
        change.oldLimit = oldLimit;
        change.newLimit = Objects.requireNonNull(newLimit, "newLimit");
        change.reason = Text.required(reason, "reason");
        change.actorKind = Objects.requireNonNull(actorKind, "actorKind");
        change.actorId = Objects.requireNonNull(actorId, "actorId");
        change.at = now;
        return change;
    }

    public UUID getId() {
        return id;
    }
}
