package com.xavier.backend.tenancy.model.dto;

import com.xavier.backend.shared.tenancy.MembershipKind;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * A membership to grant in the bound tenant (D177): the JWT's {@code (iss, sub)}, its kind and principal, and its
 * validity ({@code validUntil} is required for {@link MembershipKind#SUPPORT}, at most 24 h after {@code validFrom},
 * D183 amended; the table checks it).
 */
public record MembershipGrant(String issuer, String subject, MembershipKind kind, UUID principalId,
                              Instant validFrom, Instant validUntil) {

    public MembershipGrant {
        Objects.requireNonNull(issuer, "issuer");
        Objects.requireNonNull(subject, "subject");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(principalId, "principalId");
        Objects.requireNonNull(validFrom, "validFrom");
        if (kind == MembershipKind.SYSTEM) {
            throw new IllegalArgumentException("A SYSTEM context is never a membership");
        }
    }
}
