package com.xavier.backend.organization.model.event;

import java.time.Instant;
import java.util.UUID;
import org.springframework.modulith.NamedInterface;

/**
 * A terminal was revoked: its device token no longer works (spec §5, D26). Identity (S2) ends the POS
 * sessions opened on it. Published once per terminal (a revoked terminal cannot be revoked again).
 */
@NamedInterface("event")
public record TerminalRevoked(UUID terminalId, UUID storeId, Instant revokedAt) {
}
