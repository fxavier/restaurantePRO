package com.xavier.backend.organization.model.event;

import java.time.Instant;
import org.springframework.modulith.NamedInterface;

/**
 * The company was created at first startup (D30). Published once per installation, never on a restart
 * that finds the company already there. Identity (S2) creates the first admin from it.
 *
 * @param currency ISO-4217 code
 * @param country  ISO-3166 alpha-2 code
 *
 * <p>Exposed as named interface {@code event} on the type: a {@code package-info} here would break the
 * {@code eventsAreRecords} ArchUnit rule, which counts it as a non-record type.
 */
@NamedInterface("event")
public record CompanyBootstrapped(String name, String taxId, String country, String currency,
                                  Instant bootstrappedAt) {
}
