package com.xavier.backend.inventory.purchasing;

import static com.xavier.backend.inventory.InventoryFixtures.BO;
import static com.xavier.backend.inventory.InventoryFixtures.account;
import static com.xavier.backend.inventory.InventoryFixtures.code;
import static com.xavier.backend.inventory.InventoryFixtures.id;
import static com.xavier.backend.inventory.InventoryFixtures.idem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.inventory.InventoryFixtures;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import tools.jackson.databind.JsonNode;

/**
 * Suppliers, orders, receipts and returns through the purchasing HTTP API, as a store manager, and reads of the
 * ledger rows they post. Codes and numbers are random so tests sharing the database never collide.
 */
public final class PurchasingSteps {

    private final MockMvc mvc;
    private final JdbcClient jdbc;

    public PurchasingSteps(MockMvc mvc, JdbcClient jdbc) {
        this.mvc = mvc;
        this.jdbc = jdbc;
    }

    /** Everything purchasing needs in {@code store}. */
    public static RequestPostProcessor buyer(UUID store) {
        return account(store, Permission.PURCHASE_MANAGE, Permission.GOODS_RECEIVE, Permission.INVENTORY_VIEW,
                Permission.INVENTORY_MOVE);
    }

    public UUID newSupplier() throws Exception {
        return id(mvc.perform(idem(post(BO + "/suppliers")).with(account(null, Permission.PURCHASE_MANAGE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\": \"%s\", \"name\": \"Supplier\"}".formatted(code("SUP"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    public UUID newPack(UUID item, String quantityBase) throws Exception {
        return id(mvc.perform(idem(post(BO + "/stock-items/" + item + "/packs"))
                        .with(account(null, Permission.INVENTORY_MANAGE)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\": \"%s\", \"name\": \"Box\", \"quantity\": %s}"
                                .formatted(code("P"), quantityBase)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    /** A DRAFT receipt without an order: {@code lines} as JSON objects. */
    public ResultActions newReceipt(UUID store, UUID warehouse, UUID supplier, String lines) throws Exception {
        return mvc.perform(idem(post(BO + "/receipts")).with(buyer(store)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"warehouseId\": \"%s\", \"supplierId\": \"%s\", \"supplierDocumentRef\": \"GR-1\", \"lines\": [%s]}"
                        .formatted(warehouse, supplier, lines)));
    }

    public UUID receipt(UUID store, UUID warehouse, UUID supplier, String lines) throws Exception {
        return id(newReceipt(store, warehouse, supplier, lines).andExpect(status().isCreated()).andReturn()
                .getResponse().getContentAsString());
    }

    public static String line(UUID item, String quantity, String unitCost) {
        return "{\"stockItemId\": \"%s\", \"packQuantity\": %s, \"unitCost\": %s}".formatted(item, quantity, unitCost);
    }

    public ResultActions command(UUID store, String path, String key) throws Exception {
        return mvc.perform(idem(post(BO + path), key).with(buyer(store)));
    }

    public ResultActions command(UUID store, String path) throws Exception {
        return command(store, path, UUID.randomUUID().toString());
    }

    /** A receipt of {@code quantity} at {@code unitCost} (per base unit), confirmed. */
    public UUID confirmedReceipt(UUID store, UUID warehouse, UUID supplier, UUID item, String quantity,
                                 String unitCost) throws Exception {
        UUID receipt = receipt(store, warehouse, supplier, line(item, quantity, unitCost));
        command(store, "/receipts/" + receipt + "/confirm").andExpect(status().isOk());
        return receipt;
    }

    public JsonNode get(UUID store, String path) throws Exception {
        return InventoryFixtures.json(mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .get(BO + path).with(buyer(store))).andExpect(status().isOk()).andReturn().getResponse()
                .getContentAsString());
    }

    /** {@code type|quantity|unitCost|value} of a source's movements, in posting order. */
    public List<String> movements(String sourceType, UUID sourceId) {
        return jdbc.sql("""
                        SELECT type || '|' || quantity || '|' || unit_cost || '|' || value
                        FROM inventory.stock_movement WHERE source_type = ? AND source_id = ? ORDER BY posting_seq""")
                .params(sourceType, sourceId).query(String.class).list();
    }

    /** {@code quantity|value|averageCost}, or {@code null} when the warehouse never held the item. */
    public String balance(UUID warehouse, UUID item) {
        return jdbc.sql("""
                        SELECT quantity || '|' || value || '|' || average_cost FROM inventory.stock_balance
                        WHERE warehouse_id = ? AND stock_item_id = ?""")
                .params(warehouse, item).query(String.class).optional().orElse(null);
    }

    public long movementCount(UUID warehouse) {
        return jdbc.sql("SELECT count(*) FROM inventory.stock_movement WHERE warehouse_id = ?").param(warehouse)
                .query(Long.class).single();
    }

    /** A waste of {@code quantity} base units (valued at the average): stock consumed outside purchasing. */
    public void waste(UUID store, UUID warehouse, UUID item, String quantity) throws Exception {
        mvc.perform(idem(post(BO + "/movements/waste")).with(buyer(store)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"warehouseId": "%s", "reason": "spoiled",
                                 "lines": [{"stockItemId": "%s", "quantity": %s}]}""".formatted(warehouse, item, quantity)))
                .andExpect(status().isCreated());
    }

    public static BigDecimal decimal(JsonNode node) {
        return new BigDecimal(node.asString());
    }
}
