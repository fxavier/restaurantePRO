package com.xavier.backend.organization.model.event;

import java.time.Instant;
import java.util.UUID;
import org.springframework.modulith.NamedInterface;

/**
 * A store went from active to inactive (D45). Identity ends the POS sessions on its terminals (S10), and
 * {@code AccessPolicy.requirePos} already refuses the store before that listener runs. Published only on the
 * active → inactive transition, never for edits of an inactive store.
 */
@NamedInterface("event")
public record StoreDeactivated(UUID storeId, Instant at) {
}
