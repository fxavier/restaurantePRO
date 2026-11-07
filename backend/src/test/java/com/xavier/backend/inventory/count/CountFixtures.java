package com.xavier.backend.inventory.count;

import static com.xavier.backend.inventory.InventoryFixtures.account;
import static com.xavier.backend.inventory.InventoryFixtures.idem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.inventory.InventoryFixtures;
import com.xavier.backend.inventory.model.MovementType;
import com.xavier.backend.inventory.model.dto.PostMovement;
import com.xavier.backend.inventory.service.StockLedger;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.support.TransactionTemplate;

/** Counts driven through the HTTP API, and ledger facts posted the way S47/S48 will (StockLedger, as SYSTEM). */
public final class CountFixtures {

    public static final String COUNTS = InventoryFixtures.BO + "/counts";

    private final MockMvc mvc;
    private final StockLedger ledger;
    private final TransactionTemplate tx;
    private final JdbcClient jdbc;

    public CountFixtures(MockMvc mvc, StockLedger ledger, TransactionTemplate tx, JdbcClient jdbc) {
        this.mvc = mvc;
        this.ledger = ledger;
        this.tx = tx;
        this.jdbc = jdbc;
    }

    /** A started ALL count of {@code warehouse}. */
    public UUID startedCount(UUID store, UUID warehouse, String kind) throws Exception {
        UUID count = InventoryFixtures.id(create(store, warehouse, kind).andExpect(status().isCreated()).andReturn()
                .getResponse().getContentAsString());
        command(store, count, "start", Permission.INVENTORY_COUNT).andExpect(status().isOk());
        return count;
    }

    public ResultActions create(UUID store, UUID warehouse, String kind) throws Exception {
        return mvc.perform(idem(post(COUNTS)).with(account(store, Permission.INVENTORY_COUNT))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"warehouseId\": \"%s\", \"kind\": \"%s\", \"scope\": \"ALL\"}".formatted(warehouse, kind)));
    }

    public ResultActions line(UUID store, UUID count, UUID item, String quantity, Instant countedAt, String unitCost)
            throws Exception {
        return mvc.perform(put(COUNTS + "/" + count + "/lines/" + item)
                .with(account(store, Permission.INVENTORY_COUNT)).with(org.springframework.security.test.web
                        .servlet.request.SecurityMockMvcRequestPostProcessors.csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"countedQuantity\": %s, \"countedAt\": \"%s\"%s}".formatted(quantity, countedAt,
                        unitCost == null ? "" : ", \"unitCost\": " + unitCost)));
    }

    public ResultActions command(UUID store, UUID count, String command, Permission permission) throws Exception {
        return command(store, count, command, permission, UUID.randomUUID().toString());
    }

    public ResultActions command(UUID store, UUID count, String command, Permission permission, String key)
            throws Exception {
        return mvc.perform(idem(post(COUNTS + "/" + count + "/" + command), key).with(account(store, permission)));
    }

    public ResultActions approve(UUID store, UUID count) throws Exception {
        return command(store, count, "approve", Permission.INVENTORY_COUNT_APPROVE);
    }

    /** A receipt of {@code quantity} at {@code unitCost}, as goods receiving posts it. */
    public void receipt(UUID warehouse, UUID item, String quantity, String unitCost, Instant at) {
        posted(PostMovement.of(warehouse, item, MovementType.RECEIPT, new BigDecimal(quantity), "GOODS_RECEIPT",
                UUID.randomUUID(), day(at), at).atCost(new BigDecimal(unitCost)));
    }

    /** A sale's consumption of {@code quantity} confirmed at {@code at}, as the round listener posts it. */
    public void sale(UUID warehouse, UUID item, String quantity, Instant at) {
        posted(PostMovement.of(warehouse, item, MovementType.SALE_CONSUMPTION, new BigDecimal(quantity).negate(),
                "ORDER_LINE", UUID.randomUUID(), day(at), at));
    }

    public void posted(PostMovement movement) {
        tx.executeWithoutResult(status -> ledger.post(List.of(movement)));
    }

    /** {@code quantity|value} of the balance row, or null. */
    public String balance(UUID warehouse, UUID item) {
        return jdbc.sql("""
                        SELECT quantity || '|' || value FROM inventory.stock_balance
                        WHERE warehouse_id = ? AND stock_item_id = ?""")
                .params(warehouse, item).query(String.class).optional().orElse(null);
    }

    /** {@code type quantity unitCost occurredAt} of the count's movements, by item then type. */
    public List<String> movementsOf(UUID count) {
        return jdbc.sql("""
                        SELECT stock_item_id || ' ' || type || ' ' || quantity || ' ' || unit_cost || ' '
                               || to_char(occurred_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS')
                        FROM inventory.stock_movement WHERE source_type = 'STOCK_COUNT' AND source_id = ?
                        ORDER BY posting_seq""")
                .param(count).query(String.class).list();
    }

    private static LocalDate day(Instant at) {
        return LocalDate.ofInstant(at, ZoneOffset.UTC);
    }
}
