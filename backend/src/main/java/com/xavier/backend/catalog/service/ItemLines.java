package com.xavier.backend.catalog.service;

import com.xavier.backend.catalog.model.Item;
import com.xavier.backend.catalog.model.ItemLine;
import com.xavier.backend.catalog.model.dto.ItemKind;
import com.xavier.backend.catalog.model.dto.ItemLineRequest;
import com.xavier.backend.catalog.repository.ItemRepository;
import com.xavier.backend.catalog.repository.ItemVariantRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;

/**
 * Resolves the item references of menu options, macro entries, quick pages and automatic items against the working
 * copy when they are written: the item exists, the variant is one of its variants. Whether it is sellable is the
 * release validation's job (items change after the reference was written).
 */
@Component
class ItemLines {

    private final ItemRepository items;
    private final ItemVariantRepository variants;

    ItemLines(ItemRepository items, ItemVariantRepository variants) {
        this.items = items;
        this.variants = variants;
    }

    /** The lines as entities, with the referenced items by id; any unknown reference throws {@code invalid}. */
    Resolved resolve(List<ItemLineRequest> requests, Function<String, RuntimeException> invalid) {
        Map<UUID, Item> byId = items.findAllById(requests.stream().map(ItemLineRequest::itemId).toList()).stream()
                .collect(Collectors.toMap(Item::getId, Function.identity()));
        for (ItemLineRequest request : requests) {
            requireReference(byId.get(request.itemId()), request.itemId(), request.variantId(), invalid);
        }
        return new Resolved(requests.stream()
                .map(request -> new ItemLine(request.itemId(), request.variantId(), request.quantity())).toList(), byId);
    }

    /** The item {@code itemId} (with {@code variantId} one of its variants, when given); else throws {@code invalid}. */
    Item require(UUID itemId, UUID variantId, Function<String, RuntimeException> invalid) {
        Item item = items.findById(itemId).orElse(null);
        requireReference(item, itemId, variantId, invalid);
        return item;
    }

    private void requireReference(Item item, UUID itemId, UUID variantId, Function<String, RuntimeException> invalid) {
        if (item == null) {
            throw invalid.apply("Item " + itemId + " does not exist.");
        }
        if (variantId != null && variants.findByIdAndItemId(variantId, itemId).isEmpty()) {
            throw invalid.apply("Variant " + variantId + " is not a variant of item " + itemId + ".");
        }
    }

    record Resolved(List<ItemLine> lines, Map<UUID, Item> items) {

        /** The first referenced item of {@code kind}, if any. */
        Optional<Item> anyOfKind(ItemKind kind) {
            return items.values().stream().filter(item -> item.getKind() == kind).findFirst();
        }
    }
}
