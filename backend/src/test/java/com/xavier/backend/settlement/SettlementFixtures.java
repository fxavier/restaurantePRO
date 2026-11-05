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
import java.util.UUID;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
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
            Permission.CASH_OPERATE, Permission.CASH_VIEW, Permission.PAYMENT_RESOLVE, Permission.CASH_DAY_CLOSE};

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

    SettlementFixtures(MockMvc mvc, JdbcClient jdbc, OrdersFixtures orders, CashFixtures cash,
                       FiscalFixtures fiscal) {
        this.mvc = mvc;
        this.jdbc = jdbc;
        this.orders = orders;
        this.cash = cash;
        this.fiscal = fiscal;
    }

    /** A fresh shop and a sale of {@code quantity} items (12.50 each) in one confirmed round. */
    public Sale sale(String quantity) throws Exception {
        Shop shop = orders.shop();
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
        var request = idem(post(ATTEMPTS + "/" + attempt + "/resolve")).with(who.with(CASHIER))
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
