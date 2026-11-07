package com.xavier.backend.inventory.consumption.model;

import java.util.UUID;

/** A rule as written: {@code familyId} null is the default for every family without its own rule. */
public record PolicyRuleLine(UUID familyId, boolean tracked) {
}
