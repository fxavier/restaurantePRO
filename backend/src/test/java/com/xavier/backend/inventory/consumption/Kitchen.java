package com.xavier.backend.inventory.consumption;

import static com.xavier.backend.inventory.InventoryFixtures.account;
import static com.xavier.backend.inventory.InventoryFixtures.id;
import static com.xavier.backend.inventory.InventoryFixtures.idem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.catalog.CatalogApi;
import com.xavier.backend.catalog.ItemRef;
import com.xavier.backend.catalog.model.dto.CatalogSnapshot;
import com.xavier.backend.inventory.InventoryFixtures;
import com.xavier.backend.inventory.model.MovementType;
import com.xavier.backend.inventory.model.dto.PostMovement;
import com.xavier.backend.inventory.service.StockLedger;
import com.xavier.backend.orders.BatchFacts;
import com.xavier.backend.orders.BatchFacts.BatchLineFact;
import com.xavier.backend.orders.BatchFacts.ComponentFact;
import com.xavier.backend.orders.OrderMode;
import com.xavier.backend.orders.OrdersApi;
import com.xavier.backend.orders.VoidFacts;
import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A store live in inventory with a kitchen (the default route) and a bar (the BAR route), stock items, recipes made
 * through the HTTP API, and the facts orders and catalog would give (stubbed on the context's mocks of
 * {@link OrdersApi} and {@link CatalogApi}).
 */
public final class Kitchen {

    public static final LocalDate DAY = LocalDate.parse("2026-11-06");
    public static final String RECIPES = InventoryFixtures.BO + "/recipes";

    private final MockMvc mvc;
    private final InventoryFixtures fixtures;
    private final StockLedger ledger;
    private final TransactionTemplate tx;
    private final JdbcClient jdbc;
    private final OrdersApi orders;
    private final Map<UUID, ItemRef> catalogItems = new ConcurrentHashMap<>();
    private final Map<UUID, Map<UUID, ItemRef>> releases = new ConcurrentHashMap<>();

    public final UUID store;
    public final UUID kitchen;
    public final UUID bar;
    public final UUID defaultRoute;

    private Kitchen(MockMvc mvc, InventoryFixtures fixtures, StockLedger ledger, TransactionTemplate tx,
                    JdbcClient jdbc, OrdersApi orders, CatalogApi catalog, Instant liveFrom) throws Exception {
        this.mvc = mvc;
        this.fixtures = fixtures;
        this.ledger = ledger;
        this.tx = tx;
        this.jdbc = jdbc;
        this.orders = orders;
        org.mockito.Mockito.doAnswer(call -> {
            Collection<UUID> ids = call.getArgument(0);
            return ids.stream().map(catalogItems::get).filter(java.util.Objects::nonNull).toList();
        }).when(catalog).items(any());
        org.mockito.Mockito.doAnswer(call -> {
            UUID release = call.getArgument(1);
            Map<UUID, ItemRef> items = releases.getOrDefault(release, catalogItems);
            return new CatalogSnapshot(CatalogSnapshot.SCHEMA_VERSION, call.getArgument(0), release, 1,
                    Instant.EPOCH, "MZN", List.of(), List.of(), List.of(), items.values().stream()
                    .map(ref -> new CatalogSnapshot.Item(ref.id(), ref.code(), ref.name(), ref.familyId(), null,
                            null, null, false, null, List.of(), List.of())).toList());
        }).when(catalog).snapshot(any(), any());
        store = liveFrom == null ? fixtures.newActiveStore() : fixtures.newStore();
        if (liveFrom != null) {
            mvc.perform(idem(post(InventoryFixtures.BO + "/activations"))
                            .with(account(store, Permission.INVENTORY_MANAGE)).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"storeId\": \"%s\", \"activeFrom\": \"%s\"}".formatted(store, liveFrom)))
                    .andExpect(status().isCreated());
        }
        kitchen = fixtures.newWarehouse(store, "ALLOW");
        bar = fixtures.newWarehouse(store, "ALLOW");
        defaultRoute = route(null, kitchen);
        route("BAR", bar);
    }

    public static Kitchen open(MockMvc mvc, InventoryFixtures fixtures, StockLedger ledger, TransactionTemplate tx,
                               JdbcClient jdbc, OrdersApi orders, CatalogApi catalog) throws Exception {
        return new Kitchen(mvc, fixtures, ledger, tx, jdbc, orders, catalog, null);
    }

    /** A kitchen whose store went live at {@code liveFrom}. */
    public static Kitchen openLiveFrom(MockMvc mvc, InventoryFixtures fixtures, StockLedger ledger,
                                       TransactionTemplate tx, JdbcClient jdbc, OrdersApi orders, CatalogApi catalog,
                                       Instant liveFrom) throws Exception {
        return new Kitchen(mvc, fixtures, ledger, tx, jdbc, orders, catalog, liveFrom);
    }

    public UUID route(String destination, UUID warehouse) throws Exception {
        return id(mvc.perform(idem(post(InventoryFixtures.BO + "/consumption-routes"))
                        .with(account(null, Permission.INVENTORY_MANAGE)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeId\": \"%s\", %s \"warehouseId\": \"%s\"}".formatted(store,
                                destination == null ? "" : "\"destinationCode\": \"" + destination + "\",",
                                warehouse)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    /** Freezes the catalog as it is now into a release (as publishing would); its id. */
    public UUID publish() {
        UUID release = UUID.randomUUID();
        releases.put(release, Map.copyOf(catalogItems));
        return release;
    }

    /** Moves {@code item} to {@code family} in the working copy (published releases keep their family). */
    public void moveToFamily(UUID item, UUID family) {
        ItemRef ref = catalogItems.get(item);
        catalogItems.put(item, new ItemRef(ref.id(), ref.code(), ref.name(), family, ref.active(), ref.variantIds()));
    }

    /** A catalog item of {@code family} (random when null), with {@code variants}. */
    public UUID catalogItem(UUID family, UUID... variants) {
        UUID item = UUID.randomUUID();
        catalogItems.put(item, new ItemRef(item, InventoryFixtures.code("C"), "Sold", family == null
                ? UUID.randomUUID() : family, true, List.of(variants)));
        return item;
    }

    public UUID stockItem(String baseUnit) throws Exception {
        return fixtures.newStockItem(baseUnit);
    }

    /** {@code quantity} of {@code item} received in {@code warehouse} at {@code cost} per base unit. */
    public void receive(UUID warehouse, UUID item, String quantity, String cost) {
        tx.executeWithoutResult(status -> ledger.post(List.of(PostMovement.of(warehouse, item, MovementType.RECEIPT,
                new BigDecimal(quantity), "GOODS_RECEIPT", UUID.randomUUID(), DAY, Instant.parse(
                        "2026-11-01T06:00:00Z")).atCost(new BigDecimal(cost)))));
    }

    /** A sale recipe of {@code item} (and {@code variant}) with one version, activated now; its id. */
    public UUID saleRecipe(UUID item, UUID variant, String yield, String ingredients) throws Exception {
        UUID recipe = id(mvc.perform(idem(post(RECIPES)).with(manager()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemId\": \"%s\", %s \"name\": \"Recipe\"}".formatted(item,
                                variant == null ? "" : "\"variantId\": \"" + variant + "\",")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        version(recipe, yield, ingredients);
        activate(recipe, 1, null);
        return recipe;
    }

    public void version(UUID recipe, String yield, String ingredients) throws Exception {
        mvc.perform(idem(post(RECIPES + "/" + recipe + "/versions")).with(manager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"yieldQuantity\": %s, \"yieldUnit\": \"un\", \"ingredients\": [%s]}"
                                .formatted(yield, ingredients)))
                .andExpect(status().isCreated());
    }

    public void activate(UUID recipe, int versionNo, Instant from) throws Exception {
        mvc.perform(idem(post(RECIPES + "/" + recipe + "/versions/" + versionNo + "/activate")).with(manager())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(from == null ? "{}" : "{\"effectiveFrom\": \"%s\"}".formatted(from)))
                .andExpect(status().isOk());
    }

    public static String ingredient(UUID stockItem, String quantity, String unit) {
        return "{\"stockItemId\": \"%s\", \"quantity\": %s, \"unit\": \"%s\", \"kind\": \"INGREDIENT\"}"
                .formatted(stockItem, quantity, unit);
    }

    public static String packaging(UUID stockItem, String quantity, String unit, String... modes) {
        return "{\"stockItemId\": \"%s\", \"quantity\": %s, \"unit\": \"%s\", \"kind\": \"PACKAGING\", \"modes\": [%s]}"
                .formatted(stockItem, quantity, unit,
                        String.join(",", java.util.Arrays.stream(modes).map(m -> "\"" + m + "\"").toList()));
    }

    /** A round of this store confirmed at {@code at}, as {@code OrdersApi.batchFacts} answers it. */
    public BatchFacts round(Instant at, OrderMode mode, BatchLineFact... lines) {
        BatchFacts facts = new BatchFacts(UUID.randomUUID(), UUID.randomUUID(), store, DAY, mode, at, List.of(lines));
        when(orders.batchFacts(facts.batchId())).thenReturn(facts);
        return facts;
    }

    public static BatchLineFact line(UUID item, String quantity, String destination) {
        return new BatchLineFact(UUID.randomUUID(), item, null, UUID.randomUUID(), new BigDecimal(quantity),
                destination, List.of());
    }

    public static BatchLineFact line(UUID item, String quantity, String destination, UUID release) {
        return new BatchLineFact(UUID.randomUUID(), item, null, release, new BigDecimal(quantity), destination,
                List.of());
    }

    public static BatchLineFact variantLine(UUID item, UUID variant, String quantity) {
        return new BatchLineFact(UUID.randomUUID(), item, variant, UUID.randomUUID(), new BigDecimal(quantity),
                "KITCHEN", List.of());
    }

    public static BatchLineFact menu(UUID menuItem, String quantity, ComponentFact... components) {
        return new BatchLineFact(UUID.randomUUID(), menuItem, null, UUID.randomUUID(), new BigDecimal(quantity),
                null, List.of(components));
    }

    public static ComponentFact component(UUID item, String quantity, String destination) {
        return new ComponentFact(UUID.randomUUID(), item, null, new BigDecimal(quantity), destination);
    }

    /** A void of {@code quantity} that descends from round line {@code consumedLine}. */
    public VoidFacts voided(UUID consumedLine, String quantity, boolean wasted, Instant at) {
        VoidFacts facts = new VoidFacts(UUID.randomUUID(), UUID.randomUUID(), store, DAY, at,
                new VoidFacts.VoidedLine(UUID.randomUUID(), UUID.randomUUID(), "X", "X", Money.of("1.00", java.util.Currency.getInstance("MZN")),
                        "IVA16", new BigDecimal("16"), UUID.randomUUID()),
                new BigDecimal(quantity), Money.of("1.00", java.util.Currency.getInstance("MZN")), "error", wasted, UUID.randomUUID(), null, false,
                consumedLine);
        when(orders.voidFacts(facts.voidId())).thenReturn(facts);
        return facts;
    }

    /** The movements of a source, in posting order: type, warehouse, item, quantity, unit cost, value. */
    public List<Map<String, Object>> movements(String sourceType, UUID sourceId) {
        return jdbc.sql("""
                        SELECT type, warehouse_id, stock_item_id, quantity, unit_cost, value, reverses_movement_id,
                               recipe_version_id, sale_item_id, source_line_id, business_date, occurred_at, reason,
                               reference_unit_cost
                        FROM inventory.stock_movement WHERE source_type = ? AND source_id = ? ORDER BY posting_seq""")
                .params(sourceType, sourceId).query().listOfRows();
    }

    public long movementsOfStore() {
        return jdbc.sql("SELECT count(*) FROM inventory.stock_movement WHERE store_id = ?").param(store)
                .query(Long.class).single();
    }

    public BigDecimal quantity(UUID warehouse, UUID item) {
        return jdbc.sql("SELECT quantity FROM inventory.stock_balance WHERE warehouse_id = ? AND stock_item_id = ?")
                .params(warehouse, item).query(BigDecimal.class).optional().orElse(BigDecimal.ZERO);
    }

    public BigDecimal value(UUID warehouse, UUID item) {
        return jdbc.sql("SELECT value FROM inventory.stock_balance WHERE warehouse_id = ? AND stock_item_id = ?")
                .params(warehouse, item).query(BigDecimal.class).optional().orElse(BigDecimal.ZERO);
    }

    /** The waste reclassified from sale by wasted voids (D117 amended): quantity and value per record. */
    public List<Map<String, Object>> wasteRecords(UUID voidId) {
        return jdbc.sql("""
                        SELECT stock_item_id, warehouse_id, quantity, unit_cost, value, business_date, reason
                        FROM inventory.waste_record WHERE void_id = ? ORDER BY created_at, id""")
                .param(voidId).query().listOfRows();
    }

    public List<Map<String, Object>> exceptions() {
        return jdbc.sql("""
                        SELECT id, source_type, source_id, item_id, kind, batch_id, line_id, resolved_at, resolution,
                               late_recipe
                        FROM inventory.consumption_exception WHERE store_id = ? ORDER BY created_at, id""")
                .param(store).query().listOfRows();
    }

    private static org.springframework.test.web.servlet.request.RequestPostProcessor manager() {
        return account(null, Permission.RECIPE_MANAGE);
    }
}
