package com.xavier.backend.identity.oidc.model;

import com.xavier.backend.identity.oidc.model.dto.ExternalIdentityView;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.UuidGenerator;

/**
 * A BackOffice account's link to its identity at the installation's OpenID Provider (D144): the validated ID token's
 * {@code (iss, sub)}. Linked by the account's own session (self-linking: subjects are opaque, Entra issues a pairwise
 * one per application). One per account, one account per {@code (issuer, subject)}; the database enforces both.
 */
@Entity
@Table(schema = "identity", name = "external_identity")
public class ExternalIdentity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    private UUID id;

    @Column(nullable = false, updatable = false)
    private UUID accountId;

    @Column(nullable = false, updatable = false, length = 255)
    private String issuer;

    @Column(nullable = false, updatable = false, length = 255)
    private String subject;

    @Column(nullable = false, updatable = false)
    private Instant linkedAt;

    @Column(nullable = false, updatable = false)
    private UUID linkedByAccountId;

    private Instant lastLoginAt;

    protected ExternalIdentity() {
    }

    private ExternalIdentity(UUID accountId, String issuer, String subject, UUID linkedByAccountId, Instant now) {
        this.accountId = Objects.requireNonNull(accountId, "accountId");
        this.issuer = requireText(issuer, "issuer");
        this.subject = requireText(subject, "subject");
        this.linkedByAccountId = Objects.requireNonNull(linkedByAccountId, "linkedByAccountId");
        this.linkedAt = Objects.requireNonNull(now, "now");
    }

    /** The account's own session links it (D144). */
    public static ExternalIdentity link(UUID accountId, String issuer, String subject, Instant now) {
        return new ExternalIdentity(accountId, issuer, subject, accountId, now);
    }

    public void loggedIn(Instant now) {
        lastLoginAt = now;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public ExternalIdentityView toView() {
        return new ExternalIdentityView(accountId, issuer, subject, linkedAt, linkedByAccountId, lastLoginAt);
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
