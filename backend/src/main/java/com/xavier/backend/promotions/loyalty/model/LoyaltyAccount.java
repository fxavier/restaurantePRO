package com.xavier.backend.promotions.loyalty.model;

import com.xavier.backend.promotions.loyalty.exception.LoyaltyConflictException;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A customer's loyalty membership ("LoyaltyAccount", D110, P38: one per customer, company-wide): its status and
 * enrollment. It holds no points: the balance is the sum of the ledger. A redemption locks this row
 * ({@code FOR UPDATE}, D112, D132), so two terminals never spend the same points.
 */
@Entity
@Table(schema = "promotions", name = "loyalty_account")
public class LoyaltyAccount {

    public static final String PK = "loyalty_account_pk";
    public static final String ENTITY = "LoyaltyAccount";

    @Id
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private AccountStatus status;

    private String suspendedReason;

    @Column(nullable = false, updatable = false)
    private Instant enrolledAt;

    @Column(nullable = false, updatable = false, length = 10)
    private String enrolledByActorKind;

    @Column(updatable = false)
    private UUID enrolledByActorId;

    @Version
    private Long version;

    protected LoyaltyAccount() {
        // JPA
    }

    /** An explicit enrollment (D110: never implied by a sale), by the actor of the session. */
    public static LoyaltyAccount enroll(UUID customerId, String actorKind, UUID actorId, Instant now) {
        LoyaltyAccount account = new LoyaltyAccount();
        account.customerId = Objects.requireNonNull(customerId, "customerId");
        account.status = AccountStatus.ACTIVE;
        account.enrolledAt = now;
        account.enrolledByActorKind = actorKind;
        account.enrolledByActorId = actorId;
        return account;
    }

    /** ACTIVE → SUSPENDED with a reason; no redemption or earning while suspended. */
    public void suspend(String reason) {
        if (status != AccountStatus.ACTIVE) {
            throw LoyaltyConflictException.accountState(customerId, status, "be suspended");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("A suspension needs a reason");
        }
        status = AccountStatus.SUSPENDED;
        suspendedReason = reason.strip();
    }

    /** SUSPENDED → ACTIVE. */
    public void resume() {
        if (status != AccountStatus.SUSPENDED) {
            throw LoyaltyConflictException.accountState(customerId, status, "be resumed");
        }
        status = AccountStatus.ACTIVE;
        suspendedReason = null;
    }

    public boolean isActive() {
        return status == AccountStatus.ACTIVE;
    }

    public UUID getCustomerId() {
        return customerId;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public String getSuspendedReason() {
        return suspendedReason;
    }

    public Instant getEnrolledAt() {
        return enrolledAt;
    }

    public long getVersion() {
        return version == null ? 0 : version;
    }
}
