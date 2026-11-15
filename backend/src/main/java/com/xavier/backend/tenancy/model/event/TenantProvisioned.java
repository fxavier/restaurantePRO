package com.xavier.backend.tenancy.model.event;

import java.util.UUID;
import org.springframework.modulith.NamedInterface;

/**
 * The first link of onboarding (D181 amended): a PROVISIONING tenant exists. The organization module creates its
 * company profile and first store from it and publishes {@code CompanyProvisioned}. Published by the platform command
 * (S75) in the tenant's own binding.
 *
 * @param country    ISO-3166 alpha-2
 * @param currency   ISO-4217
 * @param adminEmail the first administrator's address: identity invites it (never logged)
 */
@NamedInterface("event")
public record TenantProvisioned(UUID tenantId, String companyName, String taxId, String country, String currency,
                                String firstStoreName, String adminEmail) {
}
