package com.xavier.backend.identity.oidc.model.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * An account's link to the OpenID Provider (D144). {@code subject} is the IdP's opaque identifier, not a credential.
 *
 * @param lastLoginAt the last federated login; {@code null} = never
 */
public record ExternalIdentityView(UUID accountId, String issuer, String subject, Instant linkedAt,
                                   UUID linkedByAccountId, Instant lastLoginAt) {
}
