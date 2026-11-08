package com.xavier.backend.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.customers.CustomersFixtures;
import com.xavier.backend.settlement.SettlementFixtures.DeliverySale;
import com.xavier.backend.settlement.SettlementFixtures.Sale;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * D127, P48 (S50 review, binding): settlement's close of a DELIVERY order that is not DELIVERED is refused with 422
 * {@code DELIVERY_NOT_DELIVERED} before s1, so no document is drafted or issued and the order is not frozen: the
 * close is not left half done. {@code POST /documents}, a partition's document and a credit sale refuse the same way (P48: no document
 * before delivery).
 * Paying in advance stays allowed; once DELIVERED the same close, or the document, succeeds.
 */
@SettlementModuleTest
class DeliveryCloseBeforeDocumentTest {

    @Autowired
    SettlementFixtures fixtures;

    @Autowired
    CustomersFixtures customers;

    @Test
    void aPaidOrderOutForDeliveryIsNotClosedNorInvoicedUntilItIsDelivered() throws Exception {
        DeliverySale delivery = fixtures.deliverySale(customers);
        Sale sale = delivery.sale();
        fixtures.payCash(sale, "15.50", "15.50").andExpect(status().isCreated()); // paid in advance
        delivery.deliveries().dispatch(delivery.shop(), sale.order(), 0, delivery.shop().courier())
                .andExpect(status().isOk());
        long version = fixtures.orderVersion(sale.cashier(), sale.order());

        fixtures.close(sale.cashier(), sale.order(), version, UUID.randomUUID().toString())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("DELIVERY_NOT_DELIVERED"));

        assertThat(fixtures.documents(sale.order())).as("no document drafted or issued").isZero();
        assertThat(fixtures.count("SELECT count(*) FROM orders.order_header WHERE id = ? AND billing_lock = "
                + "'INVOICE'", sale.order())).as("the order is not frozen").isZero();
        fixtures.settlement(sale.cashier(), sale.order()).andExpect(jsonPath("$.lifecycle").value("OPEN"))
                .andExpect(jsonPath("$.orderVersion").value(version))
                .andExpect(jsonPath("$.fiscalState").value("NONE"))
                .andExpect(jsonPath("$.financialState").value("SETTLED"));

        delivery.deliveries().delivered(delivery.shop(), sale.order(), 1).andExpect(status().isOk());
        fixtures.close(sale.cashier(), sale.order(), version, UUID.randomUUID().toString())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycle").value("CLOSED"))
                .andExpect(jsonPath("$.fiscalState").value("ISSUED"));
        assertThat(fixtures.documents(sale.order())).isOne();
    }

    @Test
    void theDocumentOfADeliveryOrderIsIssuedOnlyOnceItIsDelivered() throws Exception {
        DeliverySale delivery = fixtures.deliverySale(customers);
        Sale sale = delivery.sale();
        delivery.deliveries().dispatch(delivery.shop(), sale.order(), 0, delivery.shop().courier())
                .andExpect(status().isOk());

        fixtures.issue(sale.cashier(), sale.order(), UUID.randomUUID().toString())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("DELIVERY_NOT_DELIVERED"));

        assertThat(fixtures.documents(sale.order())).as("no document drafted or issued").isZero();
        assertThat(fixtures.count("SELECT count(*) FROM orders.order_header WHERE id = ? AND billing_lock = "
                + "'INVOICE'", sale.order())).as("the order is not frozen").isZero();

        delivery.deliveries().delivered(delivery.shop(), sale.order(), 1).andExpect(status().isOk());
        fixtures.issue(sale.cashier(), sale.order(), UUID.randomUUID().toString())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ISSUED"));
        assertThat(fixtures.documents(sale.order())).isOne();
    }

    @Test
    void aPartitionDocumentOfADeliveryOrderIsIssuedOnlyOnceItIsDelivered() throws Exception {
        DeliverySale delivery = fixtures.deliverySale(customers);
        Sale sale = delivery.sale();
        UUID line = fixtures.lineIds(sale.cashier(), sale.order()).getFirst();
        UUID partition = SettlementFixtures.id(fixtures.partitionItems(sale.cashier(), sale.order(),
                fixtures.orderVersion(sale.cashier(), sale.order()), UUID.randomUUID().toString(), line, "1")
                .andExpect(status().isCreated()));
        delivery.deliveries().dispatch(delivery.shop(), sale.order(), 0, delivery.shop().courier())
                .andExpect(status().isOk());

        fixtures.partitionDocument(sale.cashier(), partition, UUID.randomUUID().toString())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("DELIVERY_NOT_DELIVERED"));
        assertThat(fixtures.count("SELECT count(*) FROM fiscal.fiscal_document WHERE order_ref = ?", sale.order()))
                .as("no document drafted or issued").isZero();

        delivery.deliveries().delivered(delivery.shop(), sale.order(), 1).andExpect(status().isOk());
        fixtures.partitionDocument(sale.cashier(), partition, UUID.randomUUID().toString())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("ISSUED"));
    }

    @Test
    void aCreditSaleOfADeliveryOrderOutForDeliveryReservesAndDraftsNothing() throws Exception {
        DeliverySale delivery = fixtures.deliverySale(customers);
        Sale sale = delivery.sale();
        UUID customer = customers.creditCustomer("100.00");
        delivery.deliveries().dispatch(delivery.shop(), sale.order(), 0, delivery.shop().courier())
                .andExpect(status().isOk());

        fixtures.creditCharge(sale, customer, "15.50").andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("DELIVERY_NOT_DELIVERED"));

        assertThat(fixtures.count("SELECT count(*) FROM settlement.credit_charge WHERE order_id = ?", sale.order()))
                .as("no charge left").isZero();
        assertThat(fixtures.count("SELECT count(*) FROM customers.credit_reservation WHERE customer_id = ?",
                customer)).as("no credit reserved").isZero();
        assertThat(fixtures.count("SELECT count(*) FROM orders.order_header WHERE id = ? AND billing_lock = "
                + "'INVOICE'", sale.order())).as("the order is not frozen").isZero();
        assertThat(fixtures.count("SELECT count(*) FROM fiscal.fiscal_document WHERE order_ref = ?", sale.order()))
                .as("no document drafted").isZero();
    }
}
