package com.xavier.backend.reporting;

import static com.xavier.backend.inventory.InventoryFixtures.BO;
import static com.xavier.backend.inventory.InventoryFixtures.account;
import static com.xavier.backend.inventory.InventoryFixtures.id;
import static com.xavier.backend.inventory.InventoryFixtures.idem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.cash.CashFixtures;
import com.xavier.backend.cash.CashFixtures.Pos;
import com.xavier.backend.fiscal.FiscalFixtures;
import com.xavier.backend.inventory.InventoryFixtures;
import com.xavier.backend.inventory.purchasing.PurchasingSteps;
import com.xavier.backend.orders.OrdersFixtures;
import com.xavier.backend.orders.OrdersFixtures.Shop;
import com.xavier.backend.settlement.SettlementFixtures;
import com.xavier.backend.settlement.SettlementFixtures.Sale;
import java.util.UUID;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * A shop whose store is live in inventory before its first round (D122), so every round is consumed: one warehouse
 * as the default route, flour in stock (100 kg at 2.00) and a recipe of 250 g of flour per item sold, which costs
 * 0.50 an item. The store's business day starts far from the clock, so the order, the session and inventory's own
 * facts (waste, returns) all belong to one business day.
 */
@TestComponent
public class CostKitchen {

    private final MockMvc mvc;
    private final JdbcClient jdbc;
    private final OrdersFixtures orders;
    private final CashFixtures cash;
    private final FiscalFixtures fiscal;
    private final SettlementFixtures sales;
    private final InventoryFixtures inventory;

    CostKitchen(MockMvc mvc, JdbcClient jdbc, OrdersFixtures orders, CashFixtures cash, FiscalFixtures fiscal,
                SettlementFixtures sales, InventoryFixtures inventory) {
        this.mvc = mvc;
        this.jdbc = jdbc;
        this.orders = orders;
        this.cash = cash;
        this.fiscal = fiscal;
        this.sales = sales;
        this.inventory = inventory;
    }

    /** A sale whose store is live in inventory, and the warehouse and flour it consumes. */
    public record LiveSale(Sale sale, UUID warehouse, UUID flour) {

        public UUID store() {
            return sale.store();
        }
    }

    /** A shop live in inventory: its warehouse (the default route) and flour in stock. */
    public record LiveShop(Shop shop, UUID warehouse, UUID flour) {
    }

    /** A live shop and a sale of {@code quantity} items (12.50 each, 16 %) in one confirmed, consumed round. */
    public LiveSale liveSale(String quantity) throws Exception {
        LiveShop live = liveShop();
        recipe(live, live.shop().item(), "250");
        Sale sale = openSale(live.shop());
        sales.confirmRound(live.shop(), sale.cashier(), sale.order(), quantity, 0);
        return new LiveSale(sale, live.warehouse(), live.flour());
    }

    /** A new shop whose store is live from now, with 100 kg of flour at 2.00 in its default warehouse. */
    public LiveShop liveShop() throws Exception {
        Shop shop = orders.shop();
        cash.setCutoff(shop.store(), cash.cutoffFarFromNow());
        mvc.perform(idem(post(BO + "/activations")).with(account(shop.store(), Permission.INVENTORY_MANAGE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeId\": \"%s\"}".formatted(shop.store())))
                .andExpect(status().isCreated());
        UUID warehouse = inventory.newWarehouse(shop.store(), "ALLOW");
        mvc.perform(idem(post(BO + "/consumption-routes")).with(account(null, Permission.INVENTORY_MANAGE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storeId\": \"%s\", \"warehouseId\": \"%s\"}".formatted(shop.store(), warehouse)))
                .andExpect(status().isCreated());
        UUID flour = inventory.newStockItem("kg");
        mvc.perform(idem(post(BO + "/movements/manual-adjustments"))
                        .with(account(shop.store(), Permission.INVENTORY_MOVE)).contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"warehouseId": "%s", "reason": "opening",
                                 "lines": [{"stockItemId": "%s", "quantity": 100, "unitCost": 2}]}"""
                                .formatted(warehouse, flour)))
                .andExpect(status().isCreated());
        return new LiveShop(shop, warehouse, flour);
    }

    /** An active recipe of {@code grams} g of flour per {@code item} sold (2.00 a kg). */
    public void recipe(LiveShop live, UUID item, String grams) throws Exception {
        UUID recipe = id(mvc.perform(idem(post(BO + "/recipes")).with(account(null, Permission.RECIPE_MANAGE))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"itemId\": \"%s\", \"name\": \"Recipe\"}".formatted(item)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        mvc.perform(idem(post(BO + "/recipes/" + recipe + "/versions")).with(account(null, Permission.RECIPE_MANAGE))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"yieldQuantity": 1, "yieldUnit": "un", "ingredients": [
                                 {"stockItemId": "%s", "quantity": %s, "unit": "g", "kind": "INGREDIENT"}]}"""
                                .formatted(live.flour(), grams)))
                .andExpect(status().isCreated());
        mvc.perform(idem(post(BO + "/recipes/" + recipe + "/versions/1/activate"))
                        .with(account(null, Permission.RECIPE_MANAGE)).contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isOk());
    }

    /** A cashier with an open session, a fiscal series and an open order in the shop's store, no round yet. */
    public Sale openSale(Shop shop) throws Exception {
        UUID register = cash.newRegister(shop.store());
        Pos cashier = CashFixtures.pos(shop.store());
        UUID session = cash.openSession(cashier, register, "0");
        UUID series = fiscal.newSeries(shop.store(), "S" + UUID.randomUUID().toString().substring(0, 6), 1);
        UUID order = OrdersFixtures.id(orders.openedOrder(shop, cashier.employeeId(), cashier.terminalId()));
        return new Sale(shop, cashier, register, session, series, order);
    }

    /** A void of {@code quantity} of {@code line} with an approver (a mock in this context), wasted or not. */
    public ResultActions voidLine(Sale sale, UUID line, String quantity, boolean wasted) throws Exception {
        Pos who = sale.cashier();
        return mvc.perform(CashFixtures.idem(post(SettlementFixtures.ORDERS + "/" + sale.order() + "/settlement/voids"))
                .with(who.with(Permission.PAYMENT_COLLECT, Permission.ORDER_VIEW, Permission.ORDER_EDIT,
                        Permission.ORDER_VOID)).header("X-Approver-Code", "MGR")
                .header("X-Approver-Pin", "1234").contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"orderVersion": %d, "lineId": "%s", "quantity": %s, "reason": "dropped", "wasted": %s}"""
                        .formatted(sales.orderVersion(who, sale.order()), line, quantity, wasted)));
    }

    /** A manual waste of {@code quantity} kg of flour (valued at the average). */
    public void wasteFlour(LiveSale live, String quantity) throws Exception {
        new PurchasingSteps(mvc, jdbc).waste(live.store(), live.warehouse(), live.flour(), quantity);
    }

    /**
     * Sugar received at 1 (10) and at 3 (10), so on hand at an average of 2, and 5 of the second receipt returned
     * linked to its line: the return leaves at the average (10.00) while the supplier credits 15.00, a cost variance
     * of −5.00 (less cost of goods, D125 as amended). @return the return's id
     */
    public UUID linkedReturnWithVariance(LiveSale live) throws Exception {
        PurchasingSteps steps = new PurchasingSteps(mvc, jdbc);
        UUID store = live.store();
        UUID sugar = inventory.newStockItem("un");
        UUID supplier = steps.newSupplier();
        steps.confirmedReceipt(store, live.warehouse(), supplier, sugar, "10", "1");
        UUID atThree = steps.confirmedReceipt(store, live.warehouse(), supplier, sugar, "10", "3");
        String line = steps.get(store, "/receipts/" + atThree).get("lines").get(0).get("id").asString();
        UUID returned = id(mvc.perform(idem(post(BO + "/supplier-returns")).with(PurchasingSteps.buyer(store))
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"warehouseId": "%s", "goodsReceiptId": "%s", "reason": "damaged",
                                 "lines": [{"receiptLineId": "%s", "stockItemId": "%s", "quantity": 5}]}"""
                                .formatted(live.warehouse(), atThree, line, sugar)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
        steps.command(store, "/supplier-returns/" + returned + "/confirm").andExpect(status().isOk());
        return returned;
    }

    /**
     * Salt received at 1 (10), all of it wasted, then its receipt line corrected to 2: nothing is left to revalue, so
     * the correction posts no movement and the whole difference (10.00) is a cost variance (D125 as amended).
     * @return the correction's id
     */
    public UUID correctionWithNothingOnHand(LiveSale live) throws Exception {
        PurchasingSteps steps = new PurchasingSteps(mvc, jdbc);
        UUID store = live.store();
        UUID salt = inventory.newStockItem("un");
        UUID receipt = steps.confirmedReceipt(store, live.warehouse(), steps.newSupplier(), salt, "10", "1");
        String line = steps.get(store, "/receipts/" + receipt).get("lines").get(0).get("id").asString();
        steps.waste(store, live.warehouse(), salt, "10");
        return id(mvc.perform(idem(post(BO + "/receipts/" + receipt + "/lines/" + line + "/cost-corrections"))
                        .with(PurchasingSteps.buyer(store)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"newUnitCost\": 2, \"reason\": \"invoice at 2\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    /** Waits until the store's cost projections hold {@code rows} rows of {@code table} (by store). */
    public void awaitRows(String table, UUID store, long rows) {
        try {
            ReportingFixtures.await(rows + " rows of " + table, () -> jdbc.sql(
                            "SELECT count(*) FROM reporting." + table + " WHERE store_id = ?").param(store)
                    .query(Long.class).single() == rows);
        } catch (AssertionError timedOut) {
            dump(table, store);
            throw timedOut;
        }
    }

    /**
     * T1: what a timed-out {@link #awaitRows} saw, for a failure seen once under load (DiscountReportTest, 6 rows of
     * discount_record never came while every listener thread was idle): the store's rows of {@code table} and the
     * event publications that name the store.
     */
    private void dump(String table, UUID store) {
        System.out.println("awaitRows timed out: reporting." + table + " of store " + store + ": " + jdbc.sql(
                        "SELECT * FROM reporting." + table + " WHERE store_id = ?").param(store)
                .query().listOfRows());
        System.out.println("awaitRows timed out: event publications of store " + store + ": " + jdbc.sql("""
                        SELECT listener_id, event_type, status, completion_attempts, publication_date,
                               last_resubmission_date, completion_date
                        FROM event_publication WHERE serialized_event LIKE ? ORDER BY publication_date""")
                .param("%" + store + "%").query().listOfRows());
    }
}
