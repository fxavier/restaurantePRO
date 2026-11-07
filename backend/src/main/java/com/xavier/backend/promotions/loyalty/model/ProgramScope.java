package com.xavier.backend.promotions.loyalty.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.util.UUID;

/** One scope row of a program version: an item or a family whose lines earn. */
@Embeddable
public record ProgramScope(@Column(updatable = false) UUID itemId, @Column(updatable = false) UUID familyId) {

    static ProgramScope item(UUID itemId) {
        return new ProgramScope(itemId, null);
    }

    static ProgramScope family(UUID familyId) {
        return new ProgramScope(null, familyId);
    }
}
