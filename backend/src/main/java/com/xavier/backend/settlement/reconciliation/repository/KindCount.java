package com.xavier.backend.settlement.reconciliation.repository;

import com.xavier.backend.settlement.reconciliation.model.EntryKind;
import java.util.UUID;

/** How many entries of a batch are of a kind, and how many of them are open (no resolution). */
public record KindCount(UUID batchId, EntryKind kind, long count, long open) {
}
