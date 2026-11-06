package com.xavier.backend.catalog;

import static com.xavier.backend.catalog.CatalogFixtures.id;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.catalog.Publications.SellableStore;
import com.xavier.backend.catalog.exception.CatalogEntryNotFoundException;
import com.xavier.backend.catalog.exception.NoPublishedCatalogException;
import com.xavier.backend.catalog.model.dto.CatalogSnapshot;
import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** What orders (S13) read: the typed frozen catalog of a store, current or by release. */
@CatalogModuleTest
class CatalogApiTest {

    @Autowired
    CatalogApi catalogApi;

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    JsonMapper json;

    @Test
    void aStoreWithoutAPublicationHasNoCurrentSnapshot() throws Exception {
        UUID store = new CatalogFixtures(mvc).store();

        assertThatThrownBy(() -> catalogApi.currentSnapshot(store)).isInstanceOf(NoPublishedCatalogException.class);
    }

    @Test
    void theCurrentSnapshotIsTheLatestPublicationAsATypedRecord() throws Exception {
        SellableStore sellable = publications().sellableStore();
        publications().publishNew(sellable.store());
        UUID latest = publications().publishNew(sellable.store());

        CatalogSnapshot snapshot = catalogApi.currentSnapshot(sellable.store());

        assertThat(snapshot.schemaVersion()).isEqualTo(CatalogSnapshot.SCHEMA_VERSION);
        assertThat(snapshot.storeId()).isEqualTo(sellable.store());
        assertThat(snapshot.releaseId()).isEqualTo(latest);
        assertThat(snapshot.version()).isEqualTo(2);
        CatalogSnapshot.Item item = snapshot.items().stream()
                .filter(candidate -> candidate.id().equals(id(sellable.item()))).findFirst().orElseThrow();
        assertThat(item.priceOf(null)).isEqualTo(Money.of("12.50", Currency.getInstance("MZN")));
        assertThat(snapshot.taxCategories().stream().filter(category -> category.id().equals(item.taxCategoryId()))
                .findFirst().orElseThrow().rateAt(Instant.now())).contains(new BigDecimal("16"));
    }

    @Test
    void aRetiredReleaseStaysReadableByItsId() throws Exception {
        SellableStore sellable = publications().sellableStore();
        UUID first = publications().publishNew(sellable.store());
        publications().publishNew(sellable.store());

        CatalogSnapshot snapshot = catalogApi.snapshot(sellable.store(), first);

        assertThat(snapshot.releaseId()).isEqualTo(first);
        assertThat(snapshot.version()).isEqualTo(1);
    }

    @Test
    void aReleaseOfAnotherStoreOrNotYetPublishedIsNotFound() throws Exception {
        SellableStore sellable = publications().sellableStore();
        UUID published = publications().publishNew(sellable.store());
        UUID draft = id(publications().createDraft(sellable.store()));
        UUID other = new CatalogFixtures(mvc).store();

        assertThatThrownBy(() -> catalogApi.snapshot(other, published))
                .isInstanceOf(CatalogEntryNotFoundException.class);
        assertThatThrownBy(() -> catalogApi.snapshot(sellable.store(), draft))
                .isInstanceOf(CatalogEntryNotFoundException.class);
        assertThatThrownBy(() -> catalogApi.snapshot(sellable.store(), UUID.randomUUID()))
                .isInstanceOf(CatalogEntryNotFoundException.class);
    }

    @Test
    void theTypedSnapshotRoundTripsThroughJsonbUnchanged() throws Exception {
        SellableStore sellable = publications().sellableStore();
        new CatalogFixtures(mvc).variant(id(sellable.item())); // a variant without its own price: a null in the shape
        UUID release = publications().publishNew(sellable.store());
        String stored = jdbc.sql("SELECT snapshot::text FROM catalog.configuration_release WHERE id = :id")
                .param("id", release).query(String.class).single();

        CatalogSnapshot snapshot = catalogApi.snapshot(sellable.store(), release);

        // as trees: jsonb normalizes key order and whitespace
        assertThat(json.readTree(json.writeValueAsString(snapshot))).isEqualTo(json.readTree(stored));
    }

    /** D100: recipes (S47) name working-copy items and variants, published or not. */
    @Test
    void itemRefsAreReadFromTheWorkingCopyAndUnknownIdsAreLeftOut() throws Exception {
        CatalogFixtures fixtures = new CatalogFixtures(mvc);
        JsonNode withVariants = fixtures.item();
        UUID item = id(withVariants);
        UUID large = id(fixtures.variant(item));
        UUID small = id(fixtures.variant(item));
        UUID inactive = id(fixtures.item());
        jdbc.sql("UPDATE catalog.item SET availability = 'INACTIVE' WHERE id = ?").param(inactive).update();

        List<ItemRef> refs = catalogApi.items(List.of(item, inactive, UUID.randomUUID()));

        assertThat(refs).extracting(ItemRef::id).containsExactlyInAnyOrder(item, inactive);
        ItemRef ref = refs.stream().filter(candidate -> candidate.id().equals(item)).findFirst().orElseThrow();
        assertThat(ref.code()).isEqualTo(withVariants.get("code").asString());
        assertThat(ref.name()).isEqualTo("Coca-Cola 33cl");
        assertThat(ref.familyId()).hasToString(withVariants.get("familyId").asString());
        assertThat(ref.active()).isTrue();
        assertThat(ref.variantIds()).containsExactlyInAnyOrder(large, small);
        assertThat(refs).filteredOn(candidate -> candidate.id().equals(inactive)).singleElement()
                .satisfies(candidate -> {
                    assertThat(candidate.active()).isFalse();
                    assertThat(candidate.variantIds()).isEmpty();
                });
        assertThat(catalogApi.items(List.of())).isEmpty();
    }

    private Publications publications() {
        return new Publications(mvc, jdbc);
    }
}
