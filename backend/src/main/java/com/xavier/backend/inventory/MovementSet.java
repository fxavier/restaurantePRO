package com.xavier.backend.inventory;

import java.util.List;
import java.util.UUID;

/** Every movement of one source (a round line, a void, a receipt, a transfer…), in posting order. */
public record MovementSet(String sourceType, UUID sourceId, List<MovementFact> movements) {
}
