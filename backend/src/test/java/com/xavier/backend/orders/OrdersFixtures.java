package com.xavier.backend.orders;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.xavier.backend.access.Permission;
import com.xavier.backend.catalog.CatalogApi;
import com.xavier.backend.catalog.CatalogFixtures;
import com.xavier.backend.catalog.Publications;
import com.xavier.backend.dining.DiningFixtures;
import com.xavier.backend.identity.IdentityFixtures;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A store ready to sell (a published catalog with one item at 12.50 and 16% VAT, a room area with a table) built
 * through the catalog, organization and dining HTTP APIs, plus the orders POS requests the tests send.
 */
@TestComponent
public class OrdersFixtures {

    public static final String POS = "/api/v1/pos";
    public static final JsonMapper JSON = JsonMapper.builder().build();
    public static final String PIN = "4826";

    /** {@code item} sells at 12.50 with 16% VAT in catalog {@code catalogVersion}. */
    public record Shop(UUID store, UUID item, UUID area, UUID table, UUID priceList, UUID price,
                       long catalogVersion) {
    }

    private final MockMvc mvc;
    private final JdbcClient jdbc;
    private final DiningFixtures dining;
    private final CatalogApi catalogApi;

    OrdersFixtures(MockMvc mvc, JdbcClient jdbc, DiningFixtures dining, CatalogApi catalogApi) {
        this.mvc = mvc;
        this.jdbc = jdbc;
        this.dining = dining;
        this.catalogApi = catalogApi;
    }

    public Shop shop() throws Exception {
        Publications publications = new Publications(mvc, jdbc);
        Publications.SellableStore sellable = publications.sellableStore();
        publications.publishNew(sellable.store());
        UUID area = dining.createArea(sellable.store(), "SALA");
        UUID table = dining.createTable(sellable.store(), area, "M1");
        return new Shop(sellable.store(), CatalogFixtures.id(sellable.item()), area, table, sellable.priceList(),
                CatalogFixtures.id(sellable.price()), catalogApi.currentSnapshot(sellable.store()).version());
    }

    /** Republishes the store's catalog; returns the new catalog version. */
    public long republish(UUID store) throws Exception {
        new Publications(mvc, jdbc).publishNew(store);
        return catalogApi.currentSnapshot(store).version();
    }

    /** Gives the shop's item a new price in the working copy (published by {@link #republish}). */
    public void reprice(Shop shop, String amount) throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .put(CatalogFixtures.CATALOG + "/price-lists/" + shop.priceList() + "/prices/" + shop.price())
                        .with(com.xavier.backend.catalog.Actors.inStore(shop.store(), Permission.CATALOG_MANAGE))
                        .with(csrf()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"amount\": %s, \"version\": 0}".formatted(amount)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    }

    public UUID newTable(Shop shop, String code) throws Exception {
        return dining.createTable(shop.store(), shop.area(), code);
    }

    /** A manager locks {@code table} from the POS (dining). */
    public void lockTable(Shop shop, UUID table) throws Exception {
        mvc.perform(post(POS + "/tables/" + table + "/lock")
                        .with(OrdersActors.pos(shop.store(), UUID.randomUUID(), UUID.randomUUID(), Permission.TABLE_LOCK))
                        .with(csrf()).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\": \"broken chair\"}"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
    }

    public ResultActions openTable(Shop shop, UUID table, UUID employee, UUID terminal, String key)
            throws Exception {
        return mvc.perform(post(POS + "/tables/" + table + "/open")
                .with(OrdersActors.pos(shop.store(), employee, terminal, Permission.ORDER_EDIT)).with(csrf())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content("{\"guests\": 2}"));
    }

    /** Opens the shop's table as a new order; returns the order's JSON view. */
    public JsonNode openedOrder(Shop shop, UUID employee, UUID terminal) throws Exception {
        return json(openTable(shop, shop.table(), employee, terminal, UUID.randomUUID().toString())
                .andReturn().getResponse().getContentAsString());
    }

    public ResultActions addLine(Shop shop, UUID order, String body, UUID employee, UUID terminal, String key)
            throws Exception {
        return mvc.perform(post(POS + "/orders/" + order + "/lines")
                .with(OrdersActors.pos(shop.store(), employee, terminal, Permission.ORDER_EDIT)).with(csrf())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /** Adds a draft of the shop's item; returns the line's JSON view. */
    public JsonNode draft(Shop shop, UUID order, String quantity, UUID employee, UUID terminal) throws Exception {
        return json(addLine(shop, order, """
                {"itemId": "%s", "quantity": %s}""".formatted(shop.item(), quantity), employee, terminal,
                UUID.randomUUID().toString()).andReturn().getResponse().getContentAsString());
    }

    public ResultActions confirm(Shop shop, UUID order, long orderVersion, long catalogVersion, List<UUID> lines,
                                 UUID employee, UUID terminal, String key) throws Exception {
        return mvc.perform(post(POS + "/orders/" + order + "/batches")
                .with(OrdersActors.pos(shop.store(), employee, terminal, Permission.ORDER_EDIT)).with(csrf())
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content("""
                        {"orderVersion": %d, "catalogVersion": %d, "lineIds": [%s]}""".formatted(orderVersion,
                        catalogVersion, String.join(",", lines.stream().map(id -> "\"" + id + "\"").toList()))));
    }

    /** Drafts {@code quantity} of the shop's item and confirms it as a round at {@code orderVersion}; the line id. */
    public UUID confirmedLine(Shop shop, UUID order, String quantity, long orderVersion, UUID employee,
                              UUID terminal) throws Exception {
        UUID line = id(draft(shop, order, quantity, employee, terminal));
        confirm(shop, order, orderVersion, shop.catalogVersion(), List.of(line), employee, terminal,
                UUID.randomUUID().toString())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isCreated());
        return line;
    }

    /** A real employee of the shop's store with {@code role} and PIN {@link #PIN}, to approve (D40). */
    public IdentityFixtures.Employee employee(Shop shop, UUID role) throws Exception {
        return new IdentityFixtures(mvc).employee(PIN, shop.store(), role);
    }

    public JsonNode order(Shop shop, UUID order) throws Exception {
        return json(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get(POS + "/orders/" + order)
                        .with(OrdersActors.pos(shop.store(), UUID.randomUUID(), UUID.randomUUID(),
                                Permission.ORDER_VIEW)))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andReturn().getResponse().getContentAsString());
    }

    public long count(String sql, Object... params) {
        JdbcClient.StatementSpec statement = jdbc.sql(sql);
        for (Object param : params) {
            statement = statement.param(param);
        }
        return statement.query(Long.class).single();
    }

    public long audits(String action, UUID entityId) {
        return count("SELECT count(*) FROM audit.audit_event WHERE action = ? AND entity_id = ?", action,
                entityId.toString());
    }

    public static JsonNode json(String body) {
        return JSON.readTree(body);
    }

    public static UUID id(JsonNode node) {
        return UUID.fromString(node.get("id").asString());
    }

    /** Runs {@code request} on two threads released at the same instant (latch) and returns both results. */
    public static <T> List<T> race(Callable<T> first, Callable<T> second) throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
            Future<T> a = pool.submit(() -> {
                start.await();
                return first.call();
            });
            Future<T> b = pool.submit(() -> {
                start.await();
                return second.call();
            });
            start.countDown();
            return List.of(a.get(60, TimeUnit.SECONDS), b.get(60, TimeUnit.SECONDS));
        }
    }
}
