package com.xavier.backend.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.orders.BillingLine;
import com.xavier.backend.orders.LineAmounts;
import com.xavier.backend.orders.OrderBillingView;
import com.xavier.backend.orders.OrdersApi;
import com.xavier.backend.settlement.SettlementFixtures.Sale;
import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.util.Currency;
import java.util.List;
import java.util.Random;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * §14 #4, D91, A16 (the integration half; {@code model.SplitPropertyTest} is the pure half over thousands of random
 * orders, rates and cancels): seeded random orders with 3-decimal quantities split into random ITEMS partitions,
 * every partition invoiced and paid, the residual paid, then the close. Through the real endpoints and fiscal: Σ
 * document gross = due, Σ document tax = Σ order line tax, Σ document quantity per line = net quantity. With random
 * settled voids (D96): Σ document tax = Σ order line tax − Σ pending corrections.
 */
@SettlementModuleTest
class SplitPreservesTotalsTest {

    static final Money UNIT = Money.of("12.50", Currency.getInstance("MZN"));
    static final BigDecimal VAT = new BigDecimal("16");

    @Autowired
    SettlementFixtures fixtures;

    @Autowired
    com.xavier.backend.identity.ApproverVerifier approvers;

    @Autowired
    OrdersApi orders;

    @org.junit.jupiter.api.BeforeEach
    void aManagerApprovesVoids() {
        org.mockito.Mockito.when(approvers.verify(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(com.xavier.backend.access.Permission.ORDER_VOID_APPROVE),
                org.mockito.ArgumentMatchers.any())).thenReturn(UUID.randomUUID());
        org.mockito.Mockito.when(approvers.verify(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.eq(com.xavier.backend.access.Permission.ORDER_DISCOUNT_APPROVE),
                org.mockito.ArgumentMatchers.any())).thenReturn(UUID.randomUUID());
    }

    /**
     * D96: random splits, then settled voids of whole pools (each may leave a line's tax cent), some partitions
     * invoiced on their own, the rest on the residual, then the close. Σ document tax = order tax − Σ pending
     * corrections, and Σ document gross = due, always.
     */
    @Test
    void randomSettledVoidsLeaveTheDocumentsTaxAtTheOrdersLessOnlyThePendingCorrections() throws Exception {
        Random random = new Random(20261106);
        int drifted = 0;
        for (int scenario = 0; scenario < 6; scenario++) {
            drifted += splitVoidAndClose(random);
        }
        assertThat(drifted).as("scenarios whose voids left a tax cent").isPositive();
    }
    @Test
    void randomFractionalSplitsAndTheirDocumentsSumToTheOrder() throws Exception {
        Random random = new Random(1105);
        for (int scenario = 0; scenario < 4; scenario++) {
            splitAndClose(random);
        }
    }

    @Test
    void aSplitAcrossTwoTaxRatesAndItsResidualKeepTheTaxOfEachRate() throws Exception {
        Sale sale = fixtures.sale("3"); // 3 x 12.50 at 16 %
        UUID other = fixtures.confirmOtherRate(sale, "3.33", "5", "2.500"); // 2.5 x 3.33 at 5 %
        UUID first = fixtures.lineIds(sale.cashier(), sale.order()).stream().filter(id -> !id.equals(other))
                .findFirst().orElseThrow();
        JsonNode partition = SettlementFixtures.json(fixtures.partitionItems(sale.cashier(), sale.order(),
                fixtures.orderVersion(sale.cashier(), sale.order()), UUID.randomUUID().toString(), first, "1",
                other, "1.250").andExpect(status().isCreated()));
        UUID id = UUID.fromString(partition.get("id").asString());
        fixtures.partitionDocument(sale.cashier(), id, UUID.randomUUID().toString()).andExpect(status().isCreated());
        String due = partition.get("due").get("amount").asString();
        fixtures.payPartition(sale.cashier(), sale.order(), id, "CASH", due, due, UUID.randomUUID().toString())
                .andExpect(status().isCreated());
        String residual = SettlementFixtures.json(fixtures.settlement(sale.cashier(), sale.order()))
                .get("residualBalance").get("amount").asString();
        fixtures.payCash(sale, residual, residual).andExpect(status().isCreated());

        fixtures.close(sale.cashier(), sale.order(), fixtures.orderVersion(sale.cashier(), sale.order()),
                UUID.randomUUID().toString()).andExpect(status().isOk());

        assertThat(fixtures.count("SELECT count(*) FROM fiscal.fiscal_document WHERE order_ref = ?", sale.order()))
                .isEqualTo(2);
        Money vat16 = LineAmounts.of(UNIT, new BigDecimal("3"), VAT).tax();
        Money vat5 = LineAmounts.of(Money.of("3.33", UNIT.currency()), new BigDecimal("2.500"), new BigDecimal("5"))
                .tax();
        assertThat(taxAt(sale, "16")).isEqualByComparingTo(vat16.amount());
        assertThat(taxAt(sale, "5")).isEqualByComparingTo(vat5.amount());
        assertThat(fixtures.decimal("SELECT sum(total_gross) FROM fiscal.fiscal_document WHERE order_ref = ?",
                sale.order())).isEqualByComparingTo(LineAmounts.of(UNIT, new BigDecimal("3"), VAT).gross()
                .plus(LineAmounts.of(Money.of("3.33", UNIT.currency()), new BigDecimal("2.500"),
                        new BigDecimal("5")).gross()).amount());
    }

    /**
     * A41 (D101, D107): discounted, offered and split-off lines (two rates) under random ITEMS partitions, each
     * invoiced and paid, then the residual and the close. The documents carry the effective amounts: Σ document gross =
     * due, Σ document tax per rate = Σ line tax at that rate, Σ document quantity per line = its net quantity.
     */
    @Test
    void randomSplitsOfDiscountedAndOfferedLinesKeepTotalsTaxesAndQuantities() throws Exception {
        Random random = new Random(20261107);
        for (int scenario = 0; scenario < 4; scenario++) {
            discountSplitAndClose(random);
        }
    }

    private void discountSplitAndClose(Random random) throws Exception {
        Sale sale = fixtures.sale("3"); // 3 × 12.50 at 16 %
        UUID whole = fixtures.lineIds(sale.cashier(), sale.order()).getFirst();
        fixtures.confirmRound(sale.shop(), sale.cashier(), sale.order(), quantity(random),
                fixtures.orderVersion(sale.cashier(), sale.order()));
        UUID other = fixtures.confirmOtherRate(sale, "3.33", "5", "2.500");
        fixtures.offer(sale, whole, "1").andExpect(status().isOk()); // one unit offered, split off
        UUID fractional = fixtures.lineIds(sale.cashier(), sale.order()).stream()
                .filter(id -> !id.equals(whole) && !id.equals(other)).findFirst().orElseThrow();
        fixtures.percentOff(sale, fractional, null, String.valueOf(1 + random.nextInt(60)))
                .andExpect(status().isOk());
        fixtures.percentOff(sale, other, "1.250", String.valueOf(1 + random.nextInt(60))) // splits it
                .andExpect(status().isOk());
        fixtures.percentOff(sale, whole, null, "10").andExpect(status().isOk()); // a second discount of a line

        List<UUID> lines = fixtures.lineIds(sale.cashier(), sale.order());
        assertThat(lines).hasSize(5);
        for (int p = 0; p < 1 + random.nextInt(3); p++) {
            UUID line = lines.get(random.nextInt(lines.size()));
            BigDecimal pool = poolOf(coverage(sale), line);
            if (pool.signum() == 0) {
                continue;
            }
            BigDecimal quantity = BigDecimal.valueOf(1 + random.nextInt(pool.movePointRight(3).intValueExact()), 3);
            var created = fixtures.partitionItems(sale.cashier(), sale.order(),
                    fixtures.orderVersion(sale.cashier(), sale.order()), UUID.randomUUID().toString(), line,
                    quantity.toPlainString()).andReturn().getResponse();
            assertThat(created.getStatus()).as("a partition is created or refused by the rounding guard")
                    .isIn(201, 422);
            if (created.getStatus() != 201) {
                continue;
            }
            JsonNode partition = SettlementFixtures.json(created.getContentAsString());
            UUID id = UUID.fromString(partition.get("id").asString());
            fixtures.partitionDocument(sale.cashier(), id, UUID.randomUUID().toString())
                    .andExpect(status().isCreated());
            String due = partition.get("due").get("amount").asString();
            if (new BigDecimal(due).signum() > 0) {
                fixtures.payPartition(sale.cashier(), sale.order(), id, "CASH", due, due,
                        UUID.randomUUID().toString()).andExpect(status().isCreated());
            }
        }
        String residual = SettlementFixtures.json(fixtures.settlement(sale.cashier(), sale.order()))
                .get("residualBalance").get("amount").asString();
        if (new BigDecimal(residual).signum() > 0) {
            fixtures.payCash(sale, residual, residual).andExpect(status().isCreated());
        }
        OrderBillingView billed = orders.billingView(sale.order());

        fixtures.close(sale.cashier(), sale.order(), fixtures.orderVersion(sale.cashier(), sale.order()),
                UUID.randomUUID().toString()).andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycle").value("CLOSED"));

        assertThat(billed.lines().stream().filter(line -> line.unitPrice().amount().signum() == 0)).hasSize(1);
        assertThat(fixtures.decimal("SELECT sum(total_gross) FROM fiscal.fiscal_document WHERE order_ref = ?",
                sale.order())).isEqualByComparingTo(billed.due().amount());
        for (String rate : List.of("16", "5")) {
            BigDecimal tax = billed.lines().stream().filter(line -> line.ratePercent().compareTo(new BigDecimal(rate))
                    == 0).map(line -> line.tax().amount()).reduce(BigDecimal.ZERO, BigDecimal::add);
            assertThat(taxAt(sale, rate)).as("tax at %s %%", rate).isEqualByComparingTo(tax);
        }
        for (BillingLine line : billed.lines()) {
            assertThat(fixtures.decimal("""
                    SELECT sum(l.quantity) FROM fiscal.fiscal_document_line l
                    JOIN fiscal.fiscal_document d ON d.id = l.document_id
                    WHERE d.order_ref = ? AND l.source_line_id = ? AND d.status = 'ISSUED'""", sale.order(),
                    line.lineId())).isEqualByComparingTo(line.netQty());
        }
    }

    private BigDecimal taxAt(Sale sale, String rate) {
        return fixtures.decimal("""
                SELECT sum(t.tax) FROM fiscal.fiscal_document_tax t JOIN fiscal.fiscal_document d
                ON d.id = t.document_id WHERE d.order_ref = ? AND t.tax_rate = ?::numeric""", sale.order(), rate);
    }

    private void splitAndClose(Random random) throws Exception {
        Sale sale = fixtures.sale(quantity(random));
        fixtures.confirmRound(sale.shop(), sale.cashier(), sale.order(), quantity(random),
                fixtures.orderVersion(sale.cashier(), sale.order()));
        List<UUID> lines = fixtures.lineIds(sale.cashier(), sale.order());
        int partitions = 1 + random.nextInt(3);
        for (int p = 0; p < partitions; p++) {
            JsonNode coverage = coverage(sale);
            UUID line = lines.get(random.nextInt(lines.size()));
            BigDecimal pool = poolOf(coverage, line);
            if (pool.signum() == 0) {
                continue;
            }
            BigDecimal quantity = BigDecimal.valueOf(1 + random.nextInt(pool.movePointRight(3).intValueExact()), 3);
            JsonNode partition = SettlementFixtures.json(fixtures.partitionItems(sale.cashier(), sale.order(),
                    fixtures.orderVersion(sale.cashier(), sale.order()), UUID.randomUUID().toString(), line,
                    quantity.toPlainString()).andExpect(status().isCreated()));
            UUID id = UUID.fromString(partition.get("id").asString());
            fixtures.partitionDocument(sale.cashier(), id, UUID.randomUUID().toString())
                    .andExpect(status().isCreated());
            String due = partition.get("due").get("amount").asString();
            fixtures.payPartition(sale.cashier(), sale.order(), id, "CASH", due, due, UUID.randomUUID().toString())
                    .andExpect(status().isCreated());
        }
        String residual = SettlementFixtures.json(fixtures.settlement(sale.cashier(), sale.order()))
                .get("residualBalance").get("amount").asString();
        if (new BigDecimal(residual).signum() > 0) {
            fixtures.payCash(sale, residual, residual).andExpect(status().isCreated());
        }
        JsonNode coverage = coverage(sale);

        fixtures.close(sale.cashier(), sale.order(), fixtures.orderVersion(sale.cashier(), sale.order()),
                UUID.randomUUID().toString()).andExpect(status().isOk()).andExpect(jsonPath("$.lifecycle")
                .value("CLOSED"));

        Money due = Money.of("0.00", UNIT.currency());
        Money tax = Money.of("0.00", UNIT.currency());
        for (JsonNode line : coverage) {
            LineAmounts amounts = LineAmounts.of(UNIT, new BigDecimal(line.get("netQty").asString()), VAT);
            due = due.plus(amounts.gross());
            tax = tax.plus(amounts.tax());
            assertThat(fixtures.decimal("""
                    SELECT sum(l.quantity) FROM fiscal.fiscal_document_line l
                    JOIN fiscal.fiscal_document d ON d.id = l.document_id
                    WHERE d.order_ref = ? AND l.source_line_id = ? AND d.status = 'ISSUED'""", sale.order(),
                    UUID.fromString(line.get("lineId").asString())))
                    .isEqualByComparingTo(line.get("netQty").asString());
        }
        assertThat(fixtures.decimal("SELECT sum(total_gross) FROM fiscal.fiscal_document WHERE order_ref = ?",
                sale.order())).isEqualByComparingTo(due.amount());
        assertThat(fixtures.decimal("SELECT sum(total_tax) FROM fiscal.fiscal_document WHERE order_ref = ?",
                sale.order())).isEqualByComparingTo(tax.amount());
    }

    /** @return how many adjustment rows the close left (absorbed or pending) */
    private int splitVoidAndClose(Random random) throws Exception {
        Sale sale = fixtures.sale(String.valueOf(2 + random.nextInt(4)));
        fixtures.confirmRound(sale.shop(), sale.cashier(), sale.order(), quantity(random),
                fixtures.orderVersion(sale.cashier(), sale.order()));
        List<UUID> lines = fixtures.lineIds(sale.cashier(), sale.order());
        java.util.Map<UUID, String> partitions = new java.util.LinkedHashMap<>();
        for (UUID line : lines) {
            BigDecimal pool = poolOf(coverage(sale), line);
            BigDecimal quantity = pool.stripTrailingZeros().scale() <= 0 ? BigDecimal.ONE // a whole line keeps a pool
                    : BigDecimal.valueOf(1 + random.nextInt(pool.movePointRight(3).intValueExact() - 1), 3);
            var created = fixtures.partitionItems(sale.cashier(), sale.order(),
                    fixtures.orderVersion(sale.cashier(), sale.order()), UUID.randomUUID().toString(), line,
                    quantity.toPlainString()).andReturn().getResponse();
            if (created.getStatus() == 201) {
                JsonNode partition = SettlementFixtures.json(created.getContentAsString());
                partitions.put(UUID.fromString(partition.get("id").asString()),
                        partition.get("due").get("amount").asString());
            }
            BigDecimal left = poolOf(coverage(sale), line);
            if (left.signum() > 0 && random.nextInt(4) != 0) {
                int voided = fixtures.settledVoid(sale.cashier(), sale.order(),
                        fixtures.orderVersion(sale.cashier(), sale.order()), line, left.toPlainString(),
                        UUID.randomUUID().toString()).andReturn().getResponse().getStatus();
                assertThat(voided).as("a void is recorded or refused by the guard").isIn(201, 422);
            }
        }
        for (var partition : partitions.entrySet()) {
            if (random.nextBoolean()) {
                fixtures.partitionDocument(sale.cashier(), partition.getKey(), UUID.randomUUID().toString())
                        .andExpect(status().isCreated());
            }
            fixtures.payPartition(sale.cashier(), sale.order(), partition.getKey(), "CASH", partition.getValue(),
                    partition.getValue(), UUID.randomUUID().toString()).andExpect(status().isCreated());
        }
        String residual = SettlementFixtures.json(fixtures.settlement(sale.cashier(), sale.order()))
                .get("residualBalance").get("amount").asString();
        if (new BigDecimal(residual).signum() > 0) {
            fixtures.payCash(sale, residual, residual).andExpect(status().isCreated());
        }
        JsonNode coverage = coverage(sale);

        fixtures.close(sale.cashier(), sale.order(), fixtures.orderVersion(sale.cashier(), sale.order()),
                UUID.randomUUID().toString()).andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycle").value("CLOSED"));

        Money due = Money.of("0.00", UNIT.currency());
        Money tax = Money.of("0.00", UNIT.currency());
        for (JsonNode line : coverage) {
            LineAmounts amounts = LineAmounts.of(UNIT, new BigDecimal(line.get("netQty").asString()), VAT);
            due = due.plus(amounts.gross());
            tax = tax.plus(amounts.tax());
        }
        BigDecimal pending = fixtures.decimal("""
                SELECT coalesce(sum(amount), 0) FROM settlement.tax_rounding_adjustment
                WHERE order_id = ? AND kind = 'PENDING_CORRECTION'""", sale.order());
        assertThat(fixtures.decimal("SELECT sum(total_gross) FROM fiscal.fiscal_document WHERE order_ref = ?",
                sale.order())).isEqualByComparingTo(due.amount());
        assertThat(fixtures.decimal("SELECT sum(total_tax) FROM fiscal.fiscal_document WHERE order_ref = ?",
                sale.order())).isEqualByComparingTo(tax.amount().subtract(pending));
        return (int) fixtures.count("SELECT count(*) FROM settlement.tax_rounding_adjustment WHERE order_id = ?",
                sale.order());
    }

    private JsonNode coverage(Sale sale) throws Exception {
        return SettlementFixtures.json(fixtures.settlement(sale.cashier(), sale.order())).get("coverage");
    }

    private static BigDecimal poolOf(JsonNode coverage, UUID line) {
        for (JsonNode entry : coverage) {
            if (entry.get("lineId").asString().equals(line.toString())) {
                return new BigDecimal(entry.get("netQty").asString())
                        .subtract(new BigDecimal(entry.get("partitioned").asString()));
            }
        }
        throw new IllegalStateException("no line " + line);
    }

    private static String quantity(Random random) {
        return BigDecimal.valueOf(1000 + random.nextInt(4000), 3).toPlainString();
    }
}
