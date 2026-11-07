package com.xavier.backend.catalog.model.dto;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * D108/D109: releases frozen before R4 hold schema-version-1 jsonb. They never change, so the v2 record must read
 * them as they are: every item STANDARD with no menu groups, and no quick pages, macros or automatic items.
 */
class CatalogSnapshotV1CompatTest {

    static final JsonMapper JSON = JsonMapper.builder().build();

    static final String V1 = """
            {"schemaVersion": 1, "storeId": "0190a000-0000-7000-8000-000000000001",
             "releaseId": "0190a000-0000-7000-8000-000000000002", "version": 3,
             "effectiveAt": "2026-05-01T10:00:00Z", "currency": "MZN", "families": [], "units": [],
             "taxCategories": [],
             "items": [{"id": "0190a000-0000-7000-8000-000000000003", "code": "BURGER", "name": "Burger",
                        "familyId": null, "unitId": null, "taxCategoryId": null, "productionDestination": "KITCHEN",
                        "soldByWeight": false, "price": {"amount": "12.50", "currency": "MZN"},
                        "variants": [], "barcodes": []}]}""";

    @Test
    void aV1SnapshotReadsWithStandardItemsAndEmptyR4Lists() {
        CatalogSnapshot snapshot = JSON.readValue(V1, CatalogSnapshot.class);

        assertThat(snapshot.schemaVersion()).isEqualTo(1);
        assertThat(snapshot.items()).singleElement().satisfies(item -> {
            assertThat(item.kind()).isEqualTo(ItemKind.STANDARD);
            assertThat(item.menuGroups()).isEmpty();
        });
        assertThat(snapshot.quickPages()).isEmpty();
        assertThat(snapshot.macros()).isEmpty();
        assertThat(snapshot.automaticItems()).isEmpty();
    }

    @Test
    void aV2SnapshotRoundTripsItsMenusAndOperationalConfiguration() {
        UUID menuId = UUID.randomUUID();
        UUID drinkId = UUID.randomUUID();
        UUID macroId = UUID.randomUUID();
        CatalogSnapshot.Item menu = new CatalogSnapshot.Item(menuId, "MENU1", "Lunch menu", null, null, null, null,
                false, new CatalogSnapshot.Price("20.00", "MZN"), List.of(), List.of(), ItemKind.MENU,
                List.of(new CatalogSnapshot.MenuGroup("DRINK", "Drink", 1, 1,
                        List.of(new CatalogSnapshot.MenuOption(drinkId, null, new BigDecimal("1"))))));
        CatalogSnapshot written = new CatalogSnapshot(CatalogSnapshot.SCHEMA_VERSION, UUID.randomUUID(),
                UUID.randomUUID(), 1, Instant.parse("2026-11-06T10:00:00Z"), "MZN", List.of(), List.of(), List.of(),
                List.of(menu),
                List.of(new CatalogSnapshot.QuickPage(UUID.randomUUID(), "MAIN", "Main", 0,
                        List.of(new CatalogSnapshot.QuickPageEntry(menuId, null, null),
                                new CatalogSnapshot.QuickPageEntry(null, null, macroId)))),
                List.of(new CatalogSnapshot.Macro(macroId, "M1", "Two drinks",
                        List.of(new CatalogSnapshot.MacroEntry(drinkId, null, new BigDecimal("2"))))),
                List.of(new CatalogSnapshot.AutomaticItem(UUID.randomUUID(), "TABLE_OPEN_PER_GUEST", null, drinkId,
                        null, new BigDecimal("1"))));

        CatalogSnapshot read = JSON.readValue(JSON.writeValueAsString(written), CatalogSnapshot.class);

        assertThat(CatalogSnapshot.SCHEMA_VERSION).isEqualTo(2);
        assertThat(read).isEqualTo(written);
    }
}
