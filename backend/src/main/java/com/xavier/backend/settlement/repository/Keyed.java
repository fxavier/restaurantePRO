package com.xavier.backend.settlement.repository;

import java.time.Instant;
import java.util.UUID;

/** An item of a swept queue with its position in the sweep order ({@code at}, then {@code id}). */
public record Keyed(UUID id, Instant at) {
}
