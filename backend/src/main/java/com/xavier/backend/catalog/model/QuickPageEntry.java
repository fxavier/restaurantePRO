package com.xavier.backend.catalog.model;

import com.xavier.backend.catalog.exception.InvalidCatalogEntryException;
import jakarta.persistence.Embeddable;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Stream;

/** One button of a quick page: exactly one of an item, a variant or a macro (also a database CHECK). */
@Embeddable
public class QuickPageEntry {

    private UUID itemId;

    private UUID variantId;

    private UUID macroId;

    protected QuickPageEntry() {
        // JPA
    }

    /** @throws InvalidCatalogEntryException (422) unless exactly one id is given */
    public QuickPageEntry(UUID itemId, UUID variantId, UUID macroId) {
        if (Stream.of(itemId, variantId, macroId).filter(Objects::nonNull).count() != 1) {
            throw new InvalidCatalogEntryException("A quick page entry is exactly one of an item, a variant or a macro");
        }
        this.itemId = itemId;
        this.variantId = variantId;
        this.macroId = macroId;
    }

    public UUID getItemId() {
        return itemId;
    }

    public UUID getVariantId() {
        return variantId;
    }

    public UUID getMacroId() {
        return macroId;
    }
}
