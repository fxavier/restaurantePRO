package com.xavier.backend.settlement;

import static com.xavier.backend.cash.CashFixtures.idem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Actor;
import com.xavier.backend.access.Permission;
import com.xavier.backend.cash.CashFixtures;
import com.xavier.backend.cash.CashFixtures.Pos;
import com.xavier.backend.fiscal.FiscalFixtures;
import com.xavier.backend.orders.OrdersFixtures;
import com.xavier.backend.orders.OrdersFixtures.Shop;
import java.util.List;
import java.time.LocalTime;
import java.util.UUID;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import tools.jackson.databind.JsonNode;

/**
 * A sale ready to be paid, built through the HTTP APIs of the modules settlement coordinates: a shop with a
 * published catalog (12.50 an item, 16% VAT) and a table (orders), a register with an OPEN session of the cashier
 * (cash), an active fiscal series (fiscal), and an order at the table with one confirmed round. Plus the settlement
 * requests the tests send, as the cashier (every POS permission settlement checks).
 */
@TestComponent
public class SettlementFixtures {

    public static final String ATTEMPTS = "/api/v1/pos/payment-attempts";
    public static final String ORDERS = "/api/v1/pos/orders";
    public static final String METHODS = "/api/v1/backoffice/payment-methods";
    static final Permission[] CASHIER = {Permission.PAYMENT_COLLECT, Permission.ORDER_VIEW, Permission.ORDER_EDIT,
            Permission.CASH_OPERATE, Permission.CASH_VIEW, Permission.PAYMENT_RESOLVE, Permission.CASH_DAY_CLOSE,
            Permission.BILL_SPLIT, Permission.ORDER_VOID};
    public static final String PARTITIONS = "/api/v1/pos/partitions";

    /** An order of {@code shop} at its table, sold by {@code cashier}, who has an OPEN {@code session}. */
    public record Sale(Shop shop, Pos cashier, UUID register, UUID session, UUID series, UUID order) {

        public UUID store() {
            return shop.store();
        }
    }

    private final MockMvc mvc;
    private final JdbcClient jdbc;
    private final OrdersFixtures orders;
    private final CashFixtures cash;
    private final FiscalFixtures fiscal;
    private final PlatformTransactionManager transactionManager;

    SettlementFixtures(MockMvc mvc, JdbcClient jdbc, OrdersFixtures orders, CashFixtures cash,
                       FiscalFixtures fiscal, PlatformTransactionManager transactionManager) {
        this.transactionManager = transactionManager;
        this.mvc = mvc;
        this.jdbc = jdbc;
        this.orders = orders;
        this.cash = cash;
        this.fiscal = fiscal;
    }

    /**
     * A fresh shop and a sale of {@code quantity} items (12.50 each) in one confirmed round. The store's
     * business-day cutoff is far from the clock (see {@link CashFixtures#newStore()}), so the order, the session and
     * the cash day all belong to one business day whatever the time of the run.
     */
    public Sale sale(String quantity) throws Exception {
        return sale(quantity, cash.cutoffFarFromNow());
    }

    /** {@link #sale(String)} in a store whose business day starts at {@code cutoff} (for tests about the cutoff). */
    public Sale sale(String quantity, LocalTime cutoff) throws Exception {
        Shop shop = orders.shop();
        cash.setCutoff(shop.store(), cutoff);
        UUID register = cash.newRegister(shop.store());
        Pos cashier = CashFixtures.pos(shop.store());
        UUID session = cash.openSession(cashier, register, "0");
        UUID series = fiscal.newSeries(shop.store(), "S" + UUID.randomUUID().toString().substring(0, 6), 1);
        UUID order = OrdersFixtures.id(orders.openedOrder(shop, cashier.employeeId(), cashier.terminalId()));
        confirmRound(shop, cashier, order, quantity, 0);
        return new Sale(shop, cashier, register, session, series, order);
    }

    /** Another cashier of the same store with an OPEN session on a new register (a second terminal). */
    public Pos secondCashier(Sale sale) throws Exception {
        Pos other = CashFixtures.pos(sale.store());
        cash.openSession(other, cash.newRegister(sale.store()), "0");
        return other;
    }

    /** Confirms a round of {@code quantity} items on the order at {@code orderVersion}. */
    public void confirmRound(Shop shop, Pos who, UUID order, String quantity, long orderVersion) throws Exception {
        JsonNode line = orders.draft(shop, order, quantity, who.employeeId(), who.terminalId());
        orders.confirm(shop, order, orderVersion, shop.catalogVersion(), List.of(OrdersFixtures.id(line)),
                who.employeeId(), who.terminalId(), UUID.randomUUID().toString()).andExpect(status().isCreated());
    }

    public ResultActions pay(Pos who, UUID order, String method, String amount, String tendered, String key)
            throws Exception {
        String body = tendered == null
                ? """
                {"orderId": "%s", "methodCode": "%s", "amount": %s}""".formatted(order, method, amount)
                : """
                {"orderId": "%s", "methodCode": "%s", "amount": %s, "tendered": %s}""".formatted(order, method,
                amount, tendered);
        return mvc.perform(idem(post(ATTEMPTS), key).with(who.with(CASHIER))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    public ResultActions payCash(Sale sale, String amount, String tendered) throws Exception {
        return pay(sale.cashier(), sale.order(), "CASH", amount, tendered, UUID.randomUUID().toString());
    }

    public ResultActions attempt(Pos who, UUID attempt) throws Exception {
        return mvc.perform(get(ATTEMPTS + "/" + attempt).with(who.with(CASHIER)));
    }

    /** Polls the attempt (the source of truth, §7.4) until it is no longer CREATED or PENDING; returns its view. */
    public JsonNode settled(Pos who, UUID attempt) throws Exception {
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(15).toNanos();
        while (true) {
            JsonNode view = json(attempt(who, attempt).andExpect(status().isOk()));
            String state = view.get("status").asString();
            if ((!"CREATED".equals(state) && !"PENDING".equals(state)) || System.nanoTime() > deadline) {
                return view;
            }
            Thread.sleep(50);
        }
    }

    /** Resolve by provider query (no outcome, D63 executeSteps). */
    public ResultActions resolve(Pos who, UUID attempt, String key) throws Exception {
        return mvc.perform(idem(post(ATTEMPTS + "/" + attempt + "/resolve"), key).with(who.with(CASHIER))
                .contentType(MediaType.APPLICATION_JSON).content("{}"));
    }

    /** Manual reclassification (D50 §7.4) with an approver in the headers (D40). */
    public ResultActions reclassify(Pos who, UUID attempt, String outcome, String reason, String approverCode)
            throws Exception {
        return reclassify(who, attempt, outcome, reason, approverCode, UUID.randomUUID().toString());
    }

    public ResultActions reclassify(Pos who, UUID attempt, String outcome, String reason, String approverCode,
                                    String key) throws Exception {
        var request = idem(post(ATTEMPTS + "/" + attempt + "/resolve"), key).with(who.with(CASHIER))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"outcome\": \"%s\", \"reason\": \"%s\"}".formatted(outcome, reason));
        if (approverCode != null) {
            request = request.header("X-Approver-Code", approverCode).header("X-Approver-Pin", "1234");
        }
        return mvc.perform(request);
    }

    public ResultActions settlement(Pos who, UUID order) throws Exception {
        return mvc.perform(get(ORDERS + "/" + order + "/settlement").with(who.with(CASHIER)));
    }

    public ResultActions issue(Pos who, UUID order, String key) throws Exception {
        return mvc.perform(idem(post(ORDERS + "/" + order + "/documents"), key).with(who.with(CASHIER))
                .contentType(MediaType.APPLICATION_JSON).content("{}"));
    }

    public ResultActions close(Pos who, UUID order, long orderVersion, String key) throws Exception {
        return mvc.perform(idem(post(ORDERS + "/" + order + "/close"), key).with(who.with(CASHIER))
                .contentType(MediaType.APPLICATION_JSON).content("{\"orderVersion\": %d}".formatted(orderVersion)));
    }

    /** The order's current version, as the POS reads it before closing. */
    public long orderVersion(Pos who, UUID order) throws Exception {
        return json(settlement(who, order).andExpect(status().isOk())).get("orderVersion").asLong();
    }

    public JsonNode session(Pos who, UUID session) throws Exception {
        return json(mvc.perform(get(CashFixtures.SESSIONS + "/" + session).with(who.with(CASHIER)))
                .andExpect(status().isOk()));
    }

    public ResultActions closeSession(Pos who, UUID session, long version, String counts) throws Exception {
        return mvc.perform(idem(post(CashFixtures.SESSIONS + "/" + session + "/close")).with(who.with(CASHIER))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\": %d, \"counts\": [%s]}".formatted(version, counts)));
    }

    /** Closes the cashier's session counting {@code cash} in the drawer. */
    public void closeSession(Sale sale, String cash) throws Exception {
        long version = session(sale.cashier(), sale.session()).get("version").asLong();
        closeSession(sale.cashier(), sale.session(), version,
                "{\"methodCode\": \"CASH\", \"amount\": %s}".formatted(cash)).andExpect(status().isOk());
    }

    public JsonNode currentDay(Pos who) throws Exception {
        return json(mvc.perform(get(CashFixtures.POS_DAYS + "/current").with(who.with(CASHIER)))
                .andExpect(status().isOk()));
    }

    public ResultActions closeDay(Pos who) throws Exception {
        return closeDayAs(who, currentDay(who));
    }

    public ResultActions closeDayAs(Pos who, JsonNode day) throws Exception {
        return mvc.perform(idem(post(CashFixtures.POS_DAYS + "/" + day.get("id").asString() + "/operational-close"))
                .with(who.with(CASHIER)).contentType(MediaType.APPLICATION_JSON)
                .content("{\"version\": %d}".formatted(day.get("version").asLong())));
    }

    /** Pays the whole sale in cash and closes the order. */
    public void payAndClose(Sale sale) throws Exception {
        payCash(sale, "25.00", "25.00").andExpect(status().isCreated());
        close(sale.cashier(), sale.order(), orderVersion(sale.cashier(), sale.order()), UUID.randomUUID().toString())
                .andExpect(status().isOk());
    }

    // ---- R3: partitions, partition payments and documents, settled voids ----

    /** {@code POST /orders/{id}/partitions} of kind ITEMS: {@code lines} are {@code lineId, quantity} pairs. */
    public ResultActions partitionItems(Pos who, UUID order, long orderVersion, String key, Object... lines)
            throws Exception {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < lines.length; i += 2) {
            body.append(i == 0 ? "" : ", ").append("{\"lineId\": \"%s\", \"quantity\": %s}"
                    .formatted(lines[i], lines[i + 1]));
        }
        return send(who, ORDERS + "/" + order + "/partitions", key,
                "{\"orderVersion\": %d, \"kind\": \"ITEMS\", \"lines\": [%s]}".formatted(orderVersion, body));
    }

    public ResultActions partitionAmount(Pos who, UUID order, long orderVersion, String amount) throws Exception {
        return send(who, ORDERS + "/" + order + "/partitions", UUID.randomUUID().toString(),
                "{\"orderVersion\": %d, \"kind\": \"AMOUNT\", \"amount\": %s}".formatted(orderVersion, amount));
    }

    public ResultActions equalParts(Pos who, UUID order, long orderVersion, int parts) throws Exception {
        return send(who, ORDERS + "/" + order + "/partitions/equal", UUID.randomUUID().toString(),
                "{\"orderVersion\": %d, \"parts\": %d}".formatted(orderVersion, parts));
    }

    public ResultActions partitions(Pos who, UUID order) throws Exception {
        return mvc.perform(get(ORDERS + "/" + order + "/partitions").with(who.with(CASHIER)));
    }

    public ResultActions cancelPartition(Pos who, UUID partition, long version, String reason) throws Exception {
        return send(who, PARTITIONS + "/" + partition + "/cancel", UUID.randomUUID().toString(),
                "{\"version\": %d, \"reason\": \"%s\"}".formatted(version, reason));
    }

    public ResultActions partitionDocument(Pos who, UUID partition, String key) throws Exception {
        return send(who, PARTITIONS + "/" + partition + "/documents", key, "{}");
    }

    /** Pays {@code partition} of {@code order}. */
    public ResultActions payPartition(Pos who, UUID order, UUID partition, String method, String amount,
                                      String tendered, String key) throws Exception {
        String cash = tendered == null ? "" : ", \"tendered\": " + tendered;
        return send(who, ATTEMPTS, key, "{\"orderId\": \"%s\", \"partitionId\": \"%s\", \"methodCode\": \"%s\", "
                .formatted(order, partition, method) + "\"amount\": " + amount + cash + "}");
    }

    /** The settled void (D71) with an approver; the approver verifier is a mock in the settlement context. */
    public ResultActions settledVoid(Pos who, UUID order, long orderVersion, UUID line, String quantity, String key)
            throws Exception {
        return mvc.perform(idem(post(ORDERS + "/" + order + "/settlement/voids"), key).with(who.with(CASHIER))
                .header("X-Approver-Code", "MGR").header("X-Approver-Pin", "1234")
                .contentType(MediaType.APPLICATION_JSON)
                .content(("{\"orderVersion\": %d, \"lineId\": \"%s\", \"quantity\": %s, \"reason\": \"dropped\", "
                        + "\"wasted\": true}").formatted(orderVersion, line, quantity)));
    }

    /** The order's line ids in line order, from its settlement view's coverage. */
    public List<UUID> lineIds(Pos who, UUID order) throws Exception {
        List<UUID> ids = new java.util.ArrayList<>();
        json(settlement(who, order).andExpect(status().isOk())).get("coverage")
                .forEach(line -> ids.add(UUID.fromString(line.get("lineId").asString())));
        return ids;
    }

    private ResultActions send(Pos who, String path, String key, String body) throws Exception {
        return mvc.perform(idem(MockMvcRequestBuilders.post(path), key).with(who.with(CASHIER))
                .contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /**
     * Fires both requests while a test transaction holds the order's {@code order_settlement} row, waits until both
     * are blocked behind it ({@code pg_blocking_pids}), then releases it: the two really contend for the lock.
     */
    public <T> List<T> raceBehindOrderLock(UUID order, Callable<T> first, Callable<T> second) throws Exception {
        jdbc.sql("""
                INSERT INTO settlement.order_settlement (order_id, created_at) VALUES (?, now())
                ON CONFLICT (order_id) DO NOTHING""").param(order).update();
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(3)) {
            var holder = pool.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
                jdbc.sql("SELECT order_id FROM settlement.order_settlement WHERE order_id = ? FOR UPDATE")
                        .param(order).query(UUID.class).single();
                locked.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            locked.await(30, TimeUnit.SECONDS);
            var a = pool.submit(first);
            var b = pool.submit(second);
            long deadline = System.nanoTime() + java.time.Duration.ofSeconds(30).toNanos();
            while (blockedOnLocks() < 2 && System.nanoTime() < deadline) {
                Thread.sleep(5); // polls a condition; not a timing guard
            }
            long blocked = blockedOnLocks();
            release.countDown();
            holder.get(30, TimeUnit.SECONDS);
            if (blocked < 2) {
                throw new IllegalStateException("only " + blocked + " request(s) waited on the order lock");
            }
            return List.of(a.get(30, TimeUnit.SECONDS), b.get(30, TimeUnit.SECONDS));
        }
    }

    /** Backends of this database waiting on a lock (the second waiter queues behind the first, not the holder). */
    private long blockedOnLocks() {
        return jdbc.sql("SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() "
                + "AND cardinality(pg_blocking_pids(pid)) > 0").query(Long.class).single();
    }

    /** Creates an active payment method (BackOffice); returns its JSON view. */
    public JsonNode method(String code, String kind, String providerCode) throws Exception {
        String provider = providerCode == null ? "null" : "\"" + providerCode + "\"";
        return json(mvc.perform(post(METHODS).with(CashFixtures.account(null, Permission.PAYMENT_METHOD_MANAGE))
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors
                                .csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"code": "%s", "name": "%s", "kind": "%s", "providerCode": %s}"""
                                .formatted(code, code, kind, provider)))
                .andExpect(status().isCreated()));
    }

    /** A method code no other test uses. */
    public static String freshCode(String prefix) {
        return prefix + UUID.randomUUID().toString().substring(0, 8).toUpperCase().replace('-', '_');
    }

    public long count(String sql, Object... params) {
        var spec = jdbc.sql(sql);
        for (int i = 0; i < params.length; i++) {
            spec = spec.param(i + 1, params[i]);
        }
        return spec.query(Long.class).single();
    }

    public java.math.BigDecimal decimal(String sql, Object... params) {
        var spec = jdbc.sql(sql);
        for (int i = 0; i < params.length; i++) {
            spec = spec.param(i + 1, params[i]);
        }
        return spec.query(java.math.BigDecimal.class).single();
    }

    /** The order's one payment. */
    public UUID paymentOf(UUID order) {
        return jdbc.sql("SELECT id FROM settlement.payment WHERE order_id = ?").param(order).query(UUID.class)
                .single();
    }

    public long payments(UUID order) {
        return count("SELECT count(*) FROM settlement.payment WHERE order_id = ?", order);
    }

    public long receipts(UUID order) {
        return count("""
                SELECT count(*) FROM cash.cash_movement m JOIN settlement.payment p ON p.id = m.source_id
                WHERE m.source_type = 'PAYMENT' AND p.order_id = ?""", order);
    }

    public long movementsOfStore(UUID store) {
        return count("SELECT count(*) FROM cash.cash_movement WHERE store_id = ?", store);
    }

    /** The order's SALE documents: (count, ISSUED count, max number). */
    public long documents(UUID order) {
        return count("SELECT count(*) FROM fiscal.fiscal_document WHERE source_id = ?", order);
    }

    public long issuedNumber(UUID order) {
        return count("SELECT number FROM fiscal.fiscal_document WHERE source_id = ? AND status = 'ISSUED'", order);
    }

    /**
     * A CASH payment of the sale's order in the cashier's session written the way an R2 build wrote it (attempt,
     * payment and allocation; no target, terminal or business date columns). @return the payment id
     */
    public UUID r2Payment(Sale sale, String amount) {
        UUID attempt = UUID.randomUUID();
        UUID payment = UUID.randomUUID();
        java.math.BigDecimal value = new java.math.BigDecimal(amount);
        new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            jdbc.sql("""
                    INSERT INTO settlement.payment_attempt (id, order_id, store_id, terminal_id, session_id,
                        method_code, method_kind, amount, tendered, change_given, currency, status,
                        created_by_employee_id, created_at, updated_at, version)
                    VALUES (?, ?, ?, ?, ?, 'CASH', 'CASH', ?, ?, 0, 'MZN', 'SUCCEEDED', ?, now(), now(), 0)""")
                    .params(attempt, sale.order(), sale.store(), sale.cashier().terminalId(), sale.session(), value,
                            value, sale.cashier().employeeId()).update();
            jdbc.sql("""
                    INSERT INTO settlement.payment (id, attempt_id, order_id, store_id, session_id, method_code,
                        amount, currency, status, collected_by_employee_id, confirmed_at)
                    VALUES (?, ?, ?, ?, ?, 'CASH', ?, 'MZN', 'CONFIRMED', ?, now())""")
                    .params(payment, attempt, sale.order(), sale.store(), sale.session(), value,
                            sale.cashier().employeeId()).update();
            jdbc.sql("""
                    INSERT INTO settlement.payment_allocation (id, payment_id, target_type, target_id, amount,
                        currency, created_at) VALUES (?, ?, 'ORDER', ?, ?, 'MZN', now())""")
                    .params(UUID.randomUUID(), payment, sale.order(), value).update();
        });
        return payment;
    }

    public long nextNumber(UUID series) {
        return count("SELECT next_number FROM fiscal.fiscal_series WHERE id = ?", series);
    }

    public long closes(UUID order) {
        return count("SELECT count(*) FROM audit.audit_event WHERE action = 'ORDER_CLOSED' AND entity_id = ?",
                order.toString());
    }

    public boolean tableFree(UUID order) {
        return count("SELECT count(*) FROM dining.table_occupation WHERE order_id = ? AND closed_at IS NULL",
                order) == 0;
    }

    public static JsonNode json(ResultActions result) throws Exception {
        return CashFixtures.json(result.andReturn().getResponse().getContentAsString());
    }

    public static JsonNode json(String body) {
        return CashFixtures.json(body);
    }

    public static UUID id(ResultActions result) throws Exception {
        return UUID.fromString(json(result).get("id").asString());
    }

    /** Runs {@code action} on this thread as {@code who}'s POS session. */
    public static <T> T as(Pos who, java.util.function.Supplier<T> action) {
        SecurityContext previous = SecurityContextHolder.getContext();
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        Actor actor = who.actor(CASHIER);
        context.setAuthentication(CashFixtures.authenticationOf(actor));
        SecurityContextHolder.setContext(context);
        try {
            return action.get();
        } finally {
            SecurityContextHolder.setContext(previous);
        }
    }
}
