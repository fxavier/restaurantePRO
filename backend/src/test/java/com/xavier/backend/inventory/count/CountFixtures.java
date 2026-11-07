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
import com.xavier.backend.organization.MutableClock;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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
    /** The approver: another account than the counter ({@link InventoryFixtures#ACCOUNT_ID}), D119 amendment. */
    public static final UUID APPROVER_ID = UUID.fromString("00000000-0000-7000-8000-0000000000a9");

    private final MockMvc mvc;
    private final StockLedger ledger;
    private final TransactionTemplate tx;
    private final JdbcClient jdbc;
    private final MutableClock clock;
    private Instant started;

    public CountFixtures(MockMvc mvc, StockLedger ledger, TransactionTemplate tx, JdbcClient jdbc,
                         MutableClock clock) {
        this.mvc = mvc;
        this.ledger = ledger;
        this.tx = tx;
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /**
     * A started ALL count of {@code warehouse}; then the clock moves {@link #COUNTING} on, so lines can be counted
     * at {@link #at} any minute of that window (a cut-off lies between the start and now).
     */
    public UUID startedCount(UUID store, UUID warehouse, String kind) throws Exception {
        UUID count = InventoryFixtures.id(create(store, warehouse, kind).andExpect(status().isCreated()).andReturn()
                .getResponse().getContentAsString());
        start(store, count);
        return count;
    }

    public void start(UUID store, UUID count) throws Exception {
        started = clock.instant();
        command(store, count, "start", Permission.INVENTORY_COUNT).andExpect(status().isOk());
        clock.advance(COUNTING);
    }

    public static final Duration COUNTING = Duration.ofHours(6);

    /** {@code minutes} after the last count's start (to the second); negative for before it. */
    public Instant at(long minutes) {
        return started.truncatedTo(ChronoUnit.SECONDS).plusSeconds(1).plus(Duration.ofMinutes(minutes));
    }

    public Instant startedAt() {
        return started;
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

    /** Approval by {@link #APPROVER_ID}, who counted nothing. */
    public ResultActions approve(UUID store, UUID count) throws Exception {
        return approve(store, count, UUID.randomUUID().toString());
    }

    public ResultActions approve(UUID store, UUID count, String key) throws Exception {
        return decide(store, count, "approve", key, null);
    }

    /** {@code command} (approve or reject) by {@link #APPROVER_ID} with {@code INVENTORY_COUNT_APPROVE}. */
    public ResultActions decide(UUID store, UUID count, String command, String key, String body) throws Exception {
        var request = idem(post(COUNTS + "/" + count + "/" + command), key).with(org.springframework.security.test
                .web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(InventoryFixtures
                        .authenticationOf(com.xavier.backend.access.Actor.account(APPROVER_ID, java.util.Set.of(
                                Permission.INVENTORY_COUNT_APPROVE.authority(store))))));
        if (body != null) {
            request = request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        return mvc.perform(request);
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
