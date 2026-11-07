package com.xavier.backend.orders.delivery;

import static com.xavier.backend.catalog.Actors.admin;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.catalog.CatalogFixtures;
import com.xavier.backend.customers.CustomersFixtures;
import com.xavier.backend.identity.IdentityFixtures;
import com.xavier.backend.orders.OrdersActors;
import com.xavier.backend.orders.OrdersFixtures;
import com.xavier.backend.orders.OrdersFixtures.Shop;
import java.util.List;
import java.util.UUID;
import java.util.stream.StreamSupport;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;

/**
 * A delivery-ready shop for the S50 tests, built through the HTTP APIs: a published FEE item (5% tax, 3.00), a zone
 * for it, a customer with a phone and a delivery address, a courier assigned to the store, and the POS requests.
 */
public class DeliveryFixtures {

    public static final String POS = OrdersFixtures.POS;

    public record DeliveryShop(Shop shop, UUID feeItem, UUID zone, UUID customer, String phone, UUID address,
                               UUID courier, long catalogVersion) {
    }

    private final MockMvc mvc;
    private final JdbcClient jdbc;
    private final OrdersFixtures fixtures;
    private final CustomersFixtures customers;

    public DeliveryFixtures(MockMvc mvc, JdbcClient jdbc, OrdersFixtures fixtures, CustomersFixtures customers) {
        this.customers = customers;
        this.mvc = mvc;
        this.jdbc = jdbc;
        this.fixtures = fixtures;
    }

    public DeliveryShop shop() throws Exception {
        Shop shop = fixtures.shop();
        UUID fee = feeItem(shop, "5", "3.00");
        long version = fixtures.republish(shop.store());
        UUID zone = zone(shop, "Z" + CustomersFixtures.token().substring(0, 6), fee);
        String phone = "84" + CustomersFixtures.digits(7);
        UUID customer = customers.newCustomer(CustomersFixtures.pos(shop.store()),
                "Ana " + CustomersFixtures.token(), phone, null);
        UUID address = address(customer, "Rua da Entrega 7");
        UUID courier = new IdentityFixtures(mvc).employee(OrdersFixtures.PIN, shop.store(), IdentityFixtures.WAITER)
                .id();
        return new DeliveryShop(shop, fee, zone, customer, phone, address, courier, version);
    }

    /** An active FEE item with its own {@code ratePercent} tax category, priced in the shop (not yet published). */
    public UUID feeItem(Shop shop, String ratePercent, String price) throws Exception {
        CatalogFixtures catalog = new CatalogFixtures(mvc);
        JsonNode item = catalog.created(post(CatalogFixtures.CATALOG + "/items").with(admin(Permission.CATALOG_MANAGE)),
                """
                {"code": "%s", "name": "Taxa de entrega", "familyId": "%s", "unitId": "%s", "taxCategoryId": "%s",
                 "productionDestination": null, "soldByWeight": false, "kind": "FEE"}""".formatted(
                        CatalogFixtures.code("FEE"), catalog.family(), catalog.unit(),
                        catalog.taxCategory(ratePercent)));
        catalog.price(shop.store(), shop.priceList(), CatalogFixtures.id(item), null, price);
        return CatalogFixtures.id(item);
    }

    public UUID zone(Shop shop, String code, UUID feeItem) throws Exception {
        return OrdersFixtures.id(OrdersFixtures.json(createZone(shop.store(), code, feeItem)
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()));
    }

    public ResultActions createZone(UUID store, String code, UUID feeItem) throws Exception {
        return mvc.perform(post("/api/v1/backoffice/delivery-zones")
                .with(OrdersActors.backOffice(Permission.DELIVERY_ZONE_MANAGE)).with(csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content("""
                        {"storeId": "%s", "code": "%s", "name": "Zona %s", "feeItemId": "%s"}""".formatted(store,
                        code, code, feeItem)));
    }

    public void deactivateZone(UUID zone) throws Exception {
        JsonNode current = OrdersFixtures.json(mvc.perform(get("/api/v1/backoffice/delivery-zones/" + zone)
                        .with(OrdersActors.backOffice(Permission.DELIVERY_ZONE_MANAGE)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        mvc.perform(put("/api/v1/backoffice/delivery-zones/" + zone)
                        .with(OrdersActors.backOffice(Permission.DELIVERY_ZONE_MANAGE)).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"version": %d, "name": "%s", "feeItemId": "%s", "active": false}""".formatted(
                                current.get("version").asLong(), current.get("name").asString(),
                                current.get("feeItemId").asString())))
                .andExpect(status().isOk());
    }

    public UUID address(UUID customer, String line1) throws Exception {
        return UUID.fromString(OrdersFixtures.json(mvc.perform(post(CustomersFixtures.BO + "/" + customer
                                + "/addresses").with(CustomersFixtures.account(null, Permission.CUSTOMER_MANAGE))
                        .with(csrf()).header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"kind": "DELIVERY", "line1": "%s", "city": "Maputo", "notes": "portão azul"}"""
                                .formatted(line1)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()).get("id")
                .asString());
    }

    /** {@code POST /pos/orders} with mode DELIVERY and {@code delivery} (a JSON object), as {@code employee}. */
    public ResultActions open(Shop shop, String delivery, UUID employee, UUID terminal, Permission... permissions)
            throws Exception {
        return mvc.perform(post(POS + "/orders").with(OrdersActors.pos(shop.store(), employee, terminal, permissions))
                .with(csrf()).header("Idempotency-Key", UUID.randomUUID().toString())
                .contentType(MediaType.APPLICATION_JSON).content("""
                        {"mode": "DELIVERY", "delivery": %s}""".formatted(delivery)));
    }

    public static String delivery(DeliveryShop d) {
        return """
                {"customerId": "%s", "addressId": "%s", "zoneId": "%s"}""".formatted(d.customer(), d.address(),
                d.zone());
    }

    /** Opens a delivery order to the customer's saved address; its view. */
    public JsonNode opened(DeliveryShop d, UUID employee, UUID terminal) throws Exception {
        return OrdersFixtures.json(open(d.shop(), delivery(d), employee, terminal, Permission.ORDER_EDIT,
                Permission.DELIVERY_OPERATE).andExpect(status().isCreated()).andReturn().getResponse()
                .getContentAsString());
    }

    /** Opens a delivery order and confirms its fee draft plus 1 of the shop's item as one round; the order id. */
    public UUID confirmedDelivery(DeliveryShop d, UUID employee, UUID terminal) throws Exception {
        JsonNode order = opened(d, employee, terminal);
        UUID id = OrdersFixtures.id(order);
        UUID fee = UUID.fromString(order.get("lines").get(0).get("id").asString());
        UUID item = OrdersFixtures.id(fixtures.draft(d.shop(), id, "1", employee, terminal));
        fixtures.confirm(d.shop(), id, 0, d.catalogVersion(), List.of(fee, item), employee, terminal,
                UUID.randomUUID().toString()).andExpect(status().isCreated());
        return id;
    }

    public ResultActions transition(DeliveryShop d, UUID order, String command, String body) throws Exception {
        return mvc.perform(post(POS + "/deliveries/" + order + "/" + command)
                .with(OrdersActors.pos(d.shop().store(), UUID.randomUUID(), UUID.randomUUID(),
                        Permission.DELIVERY_OPERATE)).with(csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    public ResultActions dispatch(DeliveryShop d, UUID order, long version, UUID courier) throws Exception {
        return transition(d, order, "dispatch", """
                {"version": %d, "courierEmployeeId": "%s"}""".formatted(version, courier));
    }

    public ResultActions delivered(DeliveryShop d, UUID order, long version) throws Exception {
        return transition(d, order, "delivered", "{\"version\": %d}".formatted(version));
    }

    public ResultActions returned(DeliveryShop d, UUID order, long version) throws Exception {
        return transition(d, order, "returned", "{\"version\": %d, \"reason\": \"nobody home\"}".formatted(version));
    }

    public ResultActions cancel(DeliveryShop d, UUID order, long orderVersion) throws Exception {
        return mvc.perform(post(POS + "/orders/" + order + "/cancel")
                .with(OrdersActors.pos(d.shop().store(), UUID.randomUUID(), UUID.randomUUID(),
                        Permission.ORDER_CANCEL)).with(csrf())
                .header("Idempotency-Key", UUID.randomUUID().toString()).contentType(MediaType.APPLICATION_JSON)
                .content("{\"orderVersion\": %d, \"reason\": \"customer cancelled\"}".formatted(orderVersion)));
    }

    public String deliveryStatus(UUID order) {
        return jdbc.sql("SELECT status FROM orders.delivery WHERE order_id = ?").param(order).query(String.class)
                .single();
    }

    public long version(UUID order) {
        return jdbc.sql("SELECT version FROM orders.delivery WHERE order_id = ?").param(order).query(Long.class)
                .single();
    }

    public long orderVersion(UUID order) {
        return jdbc.sql("SELECT version FROM orders.order_header WHERE id = ?").param(order).query(Long.class)
                .single();
    }

    public String lifecycle(UUID order) {
        return jdbc.sql("SELECT lifecycle FROM orders.order_header WHERE id = ?").param(order).query(String.class)
                .single();
    }

    public List<String> history(UUID order) {
        return jdbc.sql("SELECT coalesce(from_status, '-') || '>' || to_status FROM orders.delivery_event "
                + "WHERE order_id = ? ORDER BY id").param(order).query(String.class).list();
    }

    /** A void fact as S14 writes it, so the order bills nothing (the tests of cancel need no approver flow). */
    public void voidAll(UUID order) {
        jdbc.sql("""
                INSERT INTO orders.order_line_void (id, order_id, line_id, quantity, reason, wasted,
                    operator_employee_id, approver_employee_id, terminal_id, voided_at)
                SELECT gen_random_uuid(), l.order_id, l.id, l.quantity, 'returned', false, gen_random_uuid(),
                    gen_random_uuid(), gen_random_uuid(), now()
                FROM orders.order_line l WHERE l.order_id = ? AND l.status = 'CONFIRMED'""").param(order).update();
    }

    public static List<JsonNode> list(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).toList();
    }
}
