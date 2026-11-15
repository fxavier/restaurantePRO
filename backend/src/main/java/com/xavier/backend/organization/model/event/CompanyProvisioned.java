package com.xavier.backend.organization.model.event;

import java.util.UUID;
import org.springframework.modulith.NamedInterface;

/**
 * The second link of onboarding (D181 amended): the tenant's company profile and first store exist. Identity creates
 * the local administrator (Administrator on all stores), invites the Keycloak user, grants the ACCOUNT membership and
 * activates the tenant. Published once per tenant, in the transaction that created the profile.
 *
 * @param adminEmail the first administrator's address (never logged)
 */
@NamedInterface("event")
public record CompanyProvisioned(UUID tenantId, UUID storeId, String adminEmail) {
}
