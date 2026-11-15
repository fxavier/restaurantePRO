package com.xavier.backend.catalog;

import static com.xavier.backend.catalog.Actors.inStore;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.OwnerDatabase;
import com.xavier.backend.access.Actor;
import com.xavier.backend.access.Permission;
import com.xavier.backend.access.StrictStoreScope;
import com.xavier.backend.architecture.RowLevelSecurityCoverageTest;
import com.xavier.backend.catalog.model.ItemPrice;
import com.xavier.backend.catalog.repository.ItemPriceRepository;
import com.xavier.backend.catalog.repository.PriceListRepository;
import com.xavier.backend.shared.Money;
import com.xavier.backend.shared.persistence.StoreProbe;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Row-level security on the catalog (D167, D168, A71, A72): the store's price list, releases, availability, daily
 * dishes and exclusions are STORE; quick pages and automatic items STORE_OR_ALL; item prices and quick-page entries
 * CHILD of their list and page; the working copy is company-wide (UNSCOPED). Strict: the test thread has no fixture
 * fallback, so every read below runs in the actor's scope only. Rows are seeded through the HTTP API (as their
 * store's manager) or by the owner.
 */
@CatalogModuleTest
@StrictStoreScope
class CatalogRowLevelSecurityTest {

    static final Currency MZN = Currency.getInstance("MZN");

    @Autowired
    MockMvc mvc;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ItemPriceRepository prices;

    @Autowired
    PriceListRepository priceLists;

    @Autowired
    StoreProbe probe;

    @Autowired
    TransactionTemplate tx;

    UUID storeA;
    UUID storeB;
    UUID item;
    UUID listA;
    UUID listB;
    UUID priceB;

    @BeforeEach
    void twoStoresWithAPriceEach() throws Exception {
        CatalogFixtures catalog = new CatalogFixtures(mvc);
        storeA = catalog.store();
        storeB = catalog.store();
        item = CatalogFixtures.id(catalog.item());
        listA = catalog.priceList(storeA);
        listB = catalog.priceList(storeB);
        catalog.price(storeA, listA, item, null, "9.00");
        priceB = CatalogFixtures.id(catalog.price(storeB, listB, item, null, "10.00"));
    }

    /** Every-store pages are seen by every store's working copy in the shared database: never leave one behind. */
    final List<UUID> pages = new ArrayList<>();

    @AfterEach
    void noActorAndNoPageLeft() {
        SecurityContextHolder.clearContext();
        for (UUID page : pages) {
            OwnerDatabase.shared().jdbc().sql("DELETE FROM catalog.quick_page WHERE id = ?").param(page).update();
        }
        pages.clear();
    }

    @Test
    void theCatalogSchemaIsClassified() {
        RowLevelSecurityCoverageTest.assertModuleClassified("catalog", "quick_page_entry");

        assertThat(protectedTables()).containsExactlyInAnyOrder("catalog.price_list", "catalog.configuration_release",
                "catalog.item_availability", "catalog.daily_dish", "catalog.store_item_exclusion",
                "catalog.quick_page", "catalog.automatic_item", "catalog.item_price", "catalog.quick_page_entry");
        assertThat(OwnerDatabase.shared().jdbc().sql("""
                        SELECT count(*) FROM shared.rls_unscoped_table WHERE table_name::text LIKE 'catalog.%'""")
                .query(Long.class).single()).as("the company-wide working copy").isEqualTo(11);
    }

    /** §14 #9: skipping AccessPolicy (the repository and plain SQL as a seam) still cannot touch store B's prices. */
    @Test
    void aStoreAManagerCannotChangeStoreBsPricesEvenThroughASeam() {
        actor(Permission.CATALOG_MANAGE.authority(storeA));

        assertThat(priceLists.findById(listB)).as("B's price list is not visible").isEmpty();
        assertThat(prices.findAllByPriceListId(listB)).as("nor its prices").isEmpty();
        assertThat(jdbc.sql("UPDATE catalog.item_price SET amount = 0 WHERE id = ?").param(priceB).update())
                .as("an update by id of B's price matches nothing").isZero();
        assertThat(jdbc.sql("DELETE FROM catalog.item_price WHERE price_list_id = ?").param(listB).update())
                .isZero();
        assertThatThrownBy(() -> tx.executeWithoutResult(status -> prices.saveAndFlush(ItemPrice.create(listB,
                UUID.randomUUID(), null, Money.of("1.00", MZN), Instant.now()))))
                .as("a new price in B's list").rootCause().hasMessageContaining("row-level security");
        assertThat(prices.findAllByPriceListId(listA)).as("A's own prices stay visible").hasSize(1);

        assertThat(OwnerDatabase.shared().jdbc().sql("SELECT amount FROM catalog.item_price WHERE id = ?")
                .param(priceB).query(BigDecimal.class).single()).isEqualByComparingTo("10.00");
    }

    /** Through the API, another store's list addressed by id is still 403 (D42 via shared.store_of), not 404. */
    @Test
    void anotherStoresPriceListByIdIsForbiddenNotMissing() throws Exception {
        mvc.perform(get(CatalogFixtures.CATALOG + "/price-lists/" + listB)
                        .with(inStore(storeA, Permission.CATALOG_MANAGE)))
                .andExpect(status().isForbidden());
        mvc.perform(put(CatalogFixtures.CATALOG + "/price-lists/" + listB + "/prices/" + priceB)
                        .with(inStore(storeA, Permission.CATALOG_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amount\": 0, \"version\": 0}"))
                .andExpect(status().isForbidden());
        mvc.perform(get(CatalogFixtures.CATALOG + "/price-lists/" + UUID.randomUUID())
                        .with(inStore(storeA, Permission.CATALOG_MANAGE)))
                .andExpect(status().isNotFound());

        actor(Permission.CATALOG_MANAGE.authority(storeA));
        assertThat(probe.storeOf("catalog.price_list", listB)).contains(storeB);
    }

    @Test
    void aStoreRowIsInsertedOnlyInTheScopeAndNeverMovesStore() {
        actor(Permission.CATALOG_MANAGE.authority(storeA));
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO catalog.price_list (id, store_id, name, currency, version, created_at, updated_at)
                VALUES (?, ?, 'Other', 'MZN', 0, now(), now())""").param(UUID.randomUUID()).param(UUID.randomUUID())
                .update()).rootCause().hasMessageContaining("row-level security");

        installationWide();
        assertThatThrownBy(() -> jdbc.sql("UPDATE catalog.price_list SET store_id = ? WHERE id = ?").param(storeB)
                .param(listA).update()).as("even an all-stores scope").rootCause()
                .hasMessageContaining("store change refused");
        assertThatThrownBy(() -> jdbc.sql("UPDATE catalog.item_price SET price_list_id = ? WHERE id = ?")
                .param(listA).param(priceB).update()).as("a child's parent key")
                .rootCause().hasMessageContaining("store change refused");
    }

    @Test
    void anAccountOfTwoStoresSeesBothAndAnInstallationWideOneSeesAll() {
        actor(Permission.CATALOG_MANAGE.authority(storeA), Permission.REPORT_VIEW.authority(storeB));
        assertThat(listsOfTheTwoStores()).containsExactlyInAnyOrder(listA, listB);

        installationWide();
        assertThat(listsOfTheTwoStores()).containsExactlyInAnyOrder(listA, listB);
    }

    @Test
    void withNoActorAndNoScopeAReadFailsClosed() {
        assertThatThrownBy(this::listsOfTheTwoStores).hasMessageContaining("store scope not set");
    }

    /**
     * STORE_OR_ALL: a company-wide quick page is read in every scope and inserted or updated only by an all-stores one
     * (a store scope's DELETE is not refused yet: a V1008 helper gap scheduled for S70).
     */
    @Test
    void aCompanyWideQuickPageIsReadEverywhereAndInsertedOrUpdatedOnlyByAllStores() {
        UUID companyWide = quickPage(null);
        UUID ofB = quickPage(storeB);

        actor(Permission.CATALOG_MANAGE.authority(storeA));
        assertThat(visiblePages(companyWide, ofB)).containsExactly(companyWide);
        assertThatThrownBy(() -> jdbc.sql("UPDATE catalog.quick_page SET name = 'x' WHERE id = ?")
                .param(companyWide).update()).as("a store scope cannot rewrite a company-wide page")
                .rootCause().hasMessageContaining("row-level security");
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO catalog.quick_page (id, store_id, code, name, position, version, created_at, updated_at)
                VALUES (?, NULL, ?, 'Page', 0, 0, now(), now())""").param(UUID.randomUUID())
                .param(CatalogFixtures.code("Q")).update()).rootCause().hasMessageContaining("row-level security");
        assertThatThrownBy(() -> jdbc.sql("""
                INSERT INTO catalog.quick_page_entry (page_id, position, item_id) VALUES (?, 1, ?)""")
                .param(ofB).param(item).update()).as("an entry on another store's page")
                .rootCause().hasMessageContaining("row-level security");

        installationWide();
        assertThat(visiblePages(companyWide, ofB)).containsExactlyInAnyOrder(companyWide, ofB);
        assertThat(jdbc.sql("INSERT INTO catalog.quick_page_entry (page_id, position, item_id) VALUES (?, 0, ?)")
                .param(companyWide).param(item).update()).isEqualTo(1);
        assertThat(jdbc.sql("UPDATE catalog.quick_page SET name = 'x' WHERE id = ?").param(companyWide).update())
                .isEqualTo(1);
    }

    /** The entries of an every-store page follow their page (D168 special): read by every scope, written by all. */
    @Test
    void theEntriesOfACompanyWidePageAreReadInEveryStoreAndInsertedOnlyByAllStores() {
        UUID companyWide = quickPage(null);
        UUID ofB = quickPage(storeB);
        for (UUID page : List.of(companyWide, ofB)) {
            OwnerDatabase.shared().jdbc().sql("INSERT INTO catalog.quick_page_entry (page_id, position, item_id) "
                    + "VALUES (?, 0, ?)").param(page).param(item).update();
        }

        actor(Permission.CATALOG_MANAGE.authority(storeA));
        assertThat(jdbc.sql("SELECT page_id FROM catalog.quick_page_entry WHERE page_id IN (?, ?)")
                .param(companyWide).param(ofB).query(UUID.class).list()).containsExactly(companyWide);
        assertThatThrownBy(() -> jdbc.sql("INSERT INTO catalog.quick_page_entry (page_id, position, item_id) "
                + "VALUES (?, 1, ?)").param(companyWide).param(item).update()).rootCause()
                .hasMessageContaining("row-level security");
        assertThat(jdbc.sql("DELETE FROM catalog.quick_page_entry WHERE page_id = ?").param(ofB).update()).isZero();

        installationWide();
        assertThatThrownBy(() -> jdbc.sql("UPDATE catalog.quick_page_entry SET page_id = ? WHERE page_id = ?")
                .param(ofB).param(companyWide).update()).rootCause().hasMessageContaining("store change refused");
    }

    /** A store-or-all entry of another store is still 403 by id; an every-store one needs an installation grant. */
    @Test
    void anotherStoresQuickPageByIdIsForbiddenNotMissing() throws Exception {
        UUID ofB = quickPage(storeB);
        UUID companyWide = quickPage(null);

        mvc.perform(get(CatalogFixtures.CATALOG + "/quick-pages/" + ofB).with(inStore(storeA, Permission.CATALOG_MANAGE)))
                .andExpect(status().isForbidden());
        mvc.perform(get(CatalogFixtures.CATALOG + "/quick-pages/" + companyWide)
                        .with(Actors.admin(Permission.CATALOG_MANAGE)))
                .andExpect(status().isOk());
        mvc.perform(get(CatalogFixtures.CATALOG + "/quick-pages/" + companyWide)
                        .with(inStore(storeA, Permission.CATALOG_MANAGE)))
                .andExpect(status().isForbidden());
        mvc.perform(get(CatalogFixtures.CATALOG + "/quick-pages/" + UUID.randomUUID())
                        .with(inStore(storeA, Permission.CATALOG_MANAGE)))
                .andExpect(status().isNotFound());
        mvc.perform(get(CatalogFixtures.CATALOG + "/quick-pages/" + ofB).with(inStore(storeB, Permission.CATALOG_MANAGE)))
                .andExpect(status().isOk());
    }

    private List<UUID> listsOfTheTwoStores() {
        return jdbc.sql("SELECT id FROM catalog.price_list WHERE store_id IN (?, ?)").param(storeA).param(storeB)
                .query(UUID.class).list();
    }

    private List<UUID> visiblePages(UUID... ids) {
        return jdbc.sql("SELECT id FROM catalog.quick_page WHERE id IN (?, ?)").param(ids[0]).param(ids[1])
                .query(UUID.class).list();
    }

    private UUID quickPage(UUID store) {
        UUID id = UUID.randomUUID();
        pages.add(id);
        OwnerDatabase.shared().jdbc().sql("""
                INSERT INTO catalog.quick_page (id, store_id, code, name, position, version, created_at, updated_at)
                VALUES (?, ?, ?, 'Page', 0, 0, now(), now())""").param(id).param(store)
                .param(CatalogFixtures.code("Q")).update();
        return id;
    }

    static List<String> protectedTables() {
        return OwnerDatabase.shared().jdbc().sql("""
                        SELECT table_name::text FROM shared.rls_protected_table WHERE table_name::text LIKE 'catalog.%'""")
                .query(String.class).list();
    }

    private static void actor(String... authorities) {
        Actor actor = Actor.account(UUID.randomUUID(), Set.of(authorities));
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated(actor, null, List.of()));
    }

    private static void installationWide() {
        actor(Permission.CATALOG_MANAGE.authority());
    }
}
