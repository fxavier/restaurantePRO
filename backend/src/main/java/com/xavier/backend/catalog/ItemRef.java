package com.xavier.backend.catalog;

import java.util.List;
import java.util.UUID;

/**
 * A working-copy item as another module names it (D100: recipes, S47): identity, family, whether it is ACTIVE, and
 * the ids of its variants. Not a price or a sellable snapshot: those come from a release.
 */
public record ItemRef(UUID id, String code, String name, UUID familyId, boolean active, List<UUID> variantIds) {
}
