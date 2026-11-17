package com.xavier.backend.tenancy.model.dto;

import com.xavier.backend.shared.tenancy.MembershipKind;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * A membership to grant in the bound tenant (D177): the JWT's {@code (iss, sub)}, its kind and principal, and how long
 * it is valid. It is valid from the moment it is granted by the database's clock, not the application's (the two
 * machines may disagree), until that moment plus {@code validFor}; {@code null} = no end. {@code validFor} is required
 * for {@link MembershipKind#SUPPORT}, at most 24 h (D183 amended; the table checks it).
 */
public record MembershipGrant(String issuer, String subject, MembershipKind kind, UUID principalId,
                              Duration validFor) {

    public MembershipGrant {
        Objects.requireNonNull(issuer, "issuer");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(principalId, "principalId");
        if (validFor != null && (validFor.isNegative() || validFor.isZero())) {
            throw new IllegalArgumentException("validFor must be positive");
        }
        if (kind == MembershipKind.SYSTEM) {
            throw new IllegalArgumentException("A SYSTEM context is never a membership");
        }
    }
}
