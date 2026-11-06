package com.xavier.backend.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.customers.CustomersFixtures;
import com.xavier.backend.fiscal.FiscalApi;
import com.xavier.backend.fiscal.model.dto.PaymentSnapshot;
import com.xavier.backend.fiscal.model.dto.PaymentSnapshot.PaidAmount;
import com.xavier.backend.fiscal.model.dto.PaymentStatus;
import com.xavier.backend.fiscal.model.event.FiscalDocumentIssued;
import com.xavier.backend.identity.ApproverVerifier;
import com.xavier.backend.settlement.SettlementFixtures.Sale;
import com.xavier.backend.shared.Money;
import java.util.Currency;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.modulith.test.PublishedEvents;
import tools.jackson.databind.JsonNode;

/**
 * D97 (A36), settlement half: the payment snapshot settlement records just before each issue, per the targets the
 * document covers, and the live {@code paymentStatus} of the views. Items are 12.50 each.
 */
@SettlementModuleTest
class DocumentPaymentBreakdownTest {

    static final Currency MZN = Currency.getInstance("MZN");

    @Autowired
    SettlementFixtures fixtures;

    @Autowired
    CustomersFixtures customers;

    @Autowired
    FiscalApi fiscal; // a spy (SettlementModuleTest)

    @Autowired
    ApproverVerifier approvers; // a mock (SettlementModuleTest)

    @AfterEach
    void realFiscal() {
        Mockito.reset(fiscal, approvers);
    }

    @Test
    void cashThenCreditIsMixedWithNothingOutstanding(PublishedEvents events) throws Exception {
        Sale sale = fixtures.sale("4"); // 50.00
        fixtures.payCash(sale, "20.00", "20.00").andExpect(status().isCreated());

        UUID document = documentOf(fixtures.creditCharge(sale, customers.creditCustomer("100.00"), "30.00")
                .andExpect(status().isCreated()));

        assertThat(fiscal.paymentSnapshot(document)).contains(new PaymentSnapshot(PaymentStatus.MIXED,
                List.of(cash("20.00")), m("30.00"), m("0.00")));
        assertThat(fixtures.count("SELECT count(*) FROM fiscal.fiscal_document WHERE id = ? AND on_account",
                document)).isEqualTo(1);
        assertThat(printout(events, document)).contains("Pagamento misto — Numerário: 20.00; A crédito: 30.00")
                .doesNotContain("A CRÉDITO", "Saldo em aberto: 0.00");
        fixtures.settlement(sale.cashier(), sale.order()).andExpect(jsonPath("$.paymentStatus").value("MIXED"))
                .andExpect(jsonPath("$.financialState").value("SETTLED"));
    }

    @Test
    void creditFirstIsMixedWithTheBalanceOutstandingAndLaterCashLeavesTheDocumentAsIssued(PublishedEvents events)
            throws Exception {
        Sale sale = fixtures.sale("4"); // 50.00
        UUID document = documentOf(fixtures.creditCharge(sale, customers.creditCustomer("100.00"), "30.00")
                .andExpect(status().isCreated()));
        PaymentSnapshot atIssue = new PaymentSnapshot(PaymentStatus.MIXED, List.of(), m("30.00"), m("20.00"));
        assertThat(fiscal.paymentSnapshot(document)).contains(atIssue);
        assertThat(printout(events, document)).contains("Pagamento misto — A crédito: 30.00", "Saldo em aberto: 20.00")
                .doesNotContain("A CRÉDITO");
        fixtures.settlement(sale.cashier(), sale.order()).andExpect(jsonPath("$.paymentStatus").value("MIXED"))
                .andExpect(jsonPath("$.financialState").value("PARTIALLY_SETTLED"));

        fixtures.payCash(sale, "20.00", "20.00").andExpect(status().isCreated());
        fixtures.close(sale.cashier(), sale.order(), fixtures.orderVersion(sale.cashier(), sale.order()),
                UUID.randomUUID().toString()).andExpect(status().isOk())
                .andExpect(jsonPath("$.financialState").value("SETTLED"))
                .andExpect(jsonPath("$.paymentStatus").value("MIXED"));

        assertThat(fiscal.paymentSnapshot(document)).as("the document states the position at its issue")
                .contains(atIssue);
    }

    @Test
    void theWholeOrderOnAccountIsOnAccount(PublishedEvents events) throws Exception {
        Sale sale = fixtures.sale("2"); // 25.00

        UUID document = documentOf(fixtures.creditCharge(sale, customers.creditCustomer("100.00"), "25.00")
                .andExpect(status().isCreated()));

        assertThat(fiscal.paymentSnapshot(document)).contains(new PaymentSnapshot(PaymentStatus.ON_ACCOUNT,
                List.of(), m("25.00"), m("0.00")));
        assertThat(printout(events, document)).contains("A CRÉDITO", "Pagamento — A crédito: 25.00");
        fixtures.settlement(sale.cashier(), sale.order()).andExpect(jsonPath("$.paymentStatus").value("ON_ACCOUNT"));
    }

    @Test
    void aPartitionDocumentIssuedBeforeAnyPaymentIsUnpaid() throws Exception {
        Sale sale = fixtures.sale("2");
        UUID partition = itemsPartition(sale, "1");

        fixtures.partitionDocument(sale.cashier(), partition, UUID.randomUUID().toString())
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.payments.status").value("UNPAID"))
                .andExpect(jsonPath("$.payments.methods.length()").value(0))
                .andExpect(jsonPath("$.payments.outstanding.amount").value(12.50));
        fixtures.partitions(sale.cashier(), sale.order())
                .andExpect(jsonPath("$[0].paymentStatus").value("UNPAID"));
    }

    @Test
    void cashAndCardAtCloseArePaidWithTwoMethodsInConfirmationOrder() throws Exception {
        Sale sale = fixtures.sale("4"); // 50.00
        String card = card();
        fixtures.payCash(sale, "20.00", "20.00").andExpect(status().isCreated());
        payByCard(sale, null, card, "30.00");

        UUID document = closeAndGetDocument(sale);

        assertThat(fiscal.paymentSnapshot(document)).contains(new PaymentSnapshot(PaymentStatus.PAID,
                List.of(cash("20.00"), new PaidAmount(card, card, m("30.00"))), m("0.00"), m("0.00")));
    }

    @Test
    void theResidualCoversWhatAnAmountPartitionWasPaid() throws Exception {
        Sale sale = fixtures.sale("4"); // 50.00
        String card = card();
        UUID partition = UUID.fromString(SettlementFixtures.json(fixtures.partitionAmount(sale.cashier(),
                sale.order(), fixtures.orderVersion(sale.cashier(), sale.order()), "20.00")
                .andExpect(status().isCreated())).get("id").asString());
        payByCard(sale, partition, card, "20.00");
        fixtures.partitions(sale.cashier(), sale.order()).andExpect(jsonPath("$[0].paymentStatus").value("PAID"));
        fixtures.payCash(sale, "30.00", "30.00").andExpect(status().isCreated());

        UUID document = closeAndGetDocument(sale);

        assertThat(fiscal.paymentSnapshot(document)).contains(new PaymentSnapshot(PaymentStatus.PAID,
                List.of(new PaidAmount(card, card, m("20.00")), cash("30.00")), m("0.00"), m("0.00")));
    }

    @Test
    void aRefundBeforeTheIssueIsNetted() throws Exception {
        Sale sale = fixtures.sale("2"); // 25.00
        String card = card();
        Mockito.when(approvers.verify(any(), any(), Mockito.eq(Permission.PAYMENT_REFUND_APPROVE), any()))
                .thenReturn(UUID.randomUUID());
        fixtures.payCash(sale, "25.00", "25.00").andExpect(status().isCreated());
        fixtures.refund(sale.cashier(), fixtures.paymentOf(sale.order()), "5.00").andExpect(status().isCreated());
        payByCard(sale, null, card, "5.00");

        UUID document = closeAndGetDocument(sale);

        assertThat(fiscal.paymentSnapshot(document)).contains(new PaymentSnapshot(PaymentStatus.PAID,
                List.of(cash("20.00"), new PaidAmount(card, card, m("5.00"))), m("0.00"), m("0.00")));
    }

    /**
     * D97, by design: no lock is held across the issue (§7.3), so a payment confirmed between the refresh and the
     * number is not on the document; the live view shows it.
     */
    @Test
    void aPaymentConfirmedBetweenTheRefreshAndTheIssueIsNotOnTheDocument() throws Exception {
        Sale sale = fixtures.sale("2"); // 25.00
        Mockito.doAnswer(call -> {
            try (var other = Executors.newSingleThreadExecutor()) {
                other.submit(() -> fixtures.payCash(sale, "25.00", "25.00").andExpect(status().isCreated()))
                        .get(30, TimeUnit.SECONDS);
            }
            return call.callRealMethod();
        }).when(fiscal).issue(any(), any());

        UUID document = SettlementFixtures.id(fixtures.issue(sale.cashier(), sale.order(),
                UUID.randomUUID().toString()).andExpect(status().isCreated()));

        assertThat(fiscal.paymentSnapshot(document)).contains(new PaymentSnapshot(PaymentStatus.UNPAID, List.of(),
                m("0.00"), m("25.00")));
        fixtures.settlement(sale.cashier(), sale.order()).andExpect(jsonPath("$.paymentStatus").value("PAID"));
    }

    private UUID closeAndGetDocument(Sale sale) throws Exception {
        return UUID.fromString(SettlementFixtures.json(fixtures.close(sale.cashier(), sale.order(),
                        fixtures.orderVersion(sale.cashier(), sale.order()), UUID.randomUUID().toString())
                .andExpect(status().isOk()).andExpect(jsonPath("$.paymentStatus").value("PAID")))
                .get("documentId").asString());
    }

    private String card() throws Exception {
        String card = SettlementFixtures.freshCode("CARD");
        fixtures.method(card, "EXTERNAL_TERMINAL", null); // named as its code
        return card;
    }

    private void payByCard(Sale sale, UUID partition, String card, String amount) throws Exception {
        var attempt = partition == null
                ? fixtures.pay(sale.cashier(), sale.order(), card, amount, null, UUID.randomUUID().toString())
                : fixtures.payPartition(sale.cashier(), sale.order(), partition, card, amount, null,
                UUID.randomUUID().toString());
        fixtures.confirm(sale.cashier(), SettlementFixtures.id(attempt.andExpect(status().isCreated())), "AUTH-1")
                .andExpect(status().isOk());
    }

    private UUID itemsPartition(Sale sale, String quantity) throws Exception {
        UUID line = fixtures.lineIds(sale.cashier(), sale.order()).getFirst();
        return UUID.fromString(SettlementFixtures.json(fixtures.partitionItems(sale.cashier(), sale.order(),
                fixtures.orderVersion(sale.cashier(), sale.order()), UUID.randomUUID().toString(), line,
                quantity).andExpect(status().isCreated())).get("id").asString());
    }

    private static UUID documentOf(org.springframework.test.web.servlet.ResultActions charge) throws Exception {
        JsonNode view = SettlementFixtures.json(charge);
        return UUID.fromString(view.get("documentId").asString());
    }

    private static List<String> printout(PublishedEvents events, UUID document) {
        return events.ofType(FiscalDocumentIssued.class).matching(e -> e.documentId().equals(document))
                .iterator().next().printout().lines();
    }

    private static PaidAmount cash(String amount) {
        return new PaidAmount("CASH", "Numerário", m(amount));
    }

    private static Money m(String amount) {
        return Money.of(amount, MZN);
    }
}
