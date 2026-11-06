package com.xavier.backend.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.Permission;
import com.xavier.backend.identity.ApproverVerifier;
import com.xavier.backend.orders.LineAmounts;
import com.xavier.backend.settlement.SettlementFixtures.Sale;
import com.xavier.backend.shared.Money;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Currency;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * §14 #4, A16 and A35 together (S34 review: "an end-to-end split property test with cancels, 2 rates and the pending
 * path"): seeded random orders with a 16 % line and a fractional 23 % line, split into random ITEMS partitions of
 * 3-decimal quantities, some cancelled again, the pools left by random settled voids, some partitions invoiced on their
 * own and the rest on the residual, everything paid, then the close. Through the real endpoints and fiscal, per
 * scenario:
 * <ul>
 *   <li>Σ document gross = due;</li>
 *   <li>per rate, Σ document tax = Σ of the lines' tax recomputed on their net quantity, less that rate's pending
 *       corrections (D96: an absorbed cent is on a document, a pending one is not);</li>
 *   <li>Σ document quantity per line = its net quantity;</li>
 *   <li>a cancelled partition is on no document.</li>
 * </ul>
 * Across the seeded scenarios both D96 outcomes occur (ABSORBED and PENDING_CORRECTION), so neither path is vacuous.
 * {@link SplitPreservesTotalsTest} keeps the single-rate variants; this class adds what they do not combine.
 */
@SettlementModuleTest
class SplitWithCancelsAndTwoRatesPropertyTest {

    static final Currency MZN = Currency.getInstance("MZN");

    @Autowired
    SettlementFixtures fixtures;

    @Autowired
    ApproverVerifier approvers;

    @Autowired
    org.springframework.jdbc.core.simple.JdbcClient jdbc;

    @BeforeEach
    void aManagerApprovesVoids() {
        Mockito.when(approvers.verify(ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.eq(Permission.ORDER_VOID_APPROVE), ArgumentMatchers.any()))
                .thenReturn(UUID.randomUUID());
    }

    @Test
    void randomSplitsWithCancelsVoidsAndTwoRatesKeepEveryTotal() throws Exception {
        Random random = new Random(20261107);
        Map<String, Integer> outcomes = new TreeMap<>();
        int cancelled = 0;
        for (int scenario = 0; scenario < 8; scenario++) {
            Outcome outcome = scenario(random);
            outcome.kinds().forEach(kind -> outcomes.merge(kind, 1, Integer::sum));
            cancelled += outcome.cancelled();
        }
        assertThat(cancelled).as("partitions cancelled across the scenarios").isPositive();
        assertThat(outcomes).as("D96 outcomes across the scenarios").containsKeys("ABSORBED", "PENDING_CORRECTION");
    }

    record Outcome(List<String> kinds, int cancelled) {
    }

    private Outcome scenario(Random random) throws Exception {
        Sale sale = fixtures.sale(String.valueOf(2 + random.nextInt(3))); // whole units of 12.50 at 16 %
        fixtures.confirmOtherRate(sale, "3.33", "23", BigDecimal.valueOf(2000 + random.nextInt(3000), 3)
                .toPlainString());
        List<UUID> lines = fixtures.lineIds(sale.cashier(), sale.order());

        Map<UUID, JsonNode> live = new LinkedHashMap<>();
        List<UUID> cancelledIds = new ArrayList<>();
        for (UUID line : lines) {
            int splits = 1 + random.nextInt(2);
            for (int s = 0; s < splits; s++) {
                BigDecimal pool = poolOf(sale, line);
                if (pool.compareTo(new BigDecimal("0.002")) < 0) {
                    break;
                }
                // leave at least 0.001 in the pool, so the void below has something to empty
                BigDecimal quantity = BigDecimal.valueOf(1 + random.nextInt(pool.movePointRight(3).intValueExact()
                        - 1), 3);
                JsonNode partition = SettlementFixtures.json(fixtures.partitionItems(sale.cashier(), sale.order(),
                        fixtures.orderVersion(sale.cashier(), sale.order()), UUID.randomUUID().toString(), line,
                        quantity.toPlainString()).andExpect(status().isCreated()));
                live.put(UUID.fromString(partition.get("id").asString()), partition);
            }
        }
        for (var partition : new ArrayList<>(live.entrySet())) {
            if (random.nextInt(3) == 0) {
                fixtures.cancelPartition(sale.cashier(), partition.getKey(), partition.getValue().get("version")
                        .asLong(), "diner changed their mind").andExpect(status().isOk());
                live.remove(partition.getKey());
                cancelledIds.add(partition.getKey());
            }
        }
        for (UUID line : lines) {
            BigDecimal left = poolOf(sale, line);
            if (left.signum() > 0 && random.nextInt(4) != 0) {
                int voided = fixtures.settledVoid(sale.cashier(), sale.order(),
                        fixtures.orderVersion(sale.cashier(), sale.order()), line, left.toPlainString(),
                        UUID.randomUUID().toString()).andReturn().getResponse().getStatus();
                assertThat(voided).as("a void is recorded or refused by its guard").isIn(201, 422);
            }
        }
        for (var partition : live.entrySet()) {
            if (random.nextBoolean()) {
                fixtures.partitionDocument(sale.cashier(), partition.getKey(), UUID.randomUUID().toString())
                        .andExpect(status().isCreated());
            }
            String due = SettlementFixtures.json(fixtures.partitions(sale.cashier(), sale.order())).valueStream()
                    .filter(p -> p.get("id").asString().equals(partition.getKey().toString())).findFirst()
                    .orElseThrow().get("balance").get("amount").asString();
            if (new BigDecimal(due).signum() > 0) {
                fixtures.payPartition(sale.cashier(), sale.order(), partition.getKey(), "CASH", due, due,
                        UUID.randomUUID().toString()).andExpect(status().isCreated());
            }
        }
        String residual = SettlementFixtures.json(fixtures.settlement(sale.cashier(), sale.order()))
                .get("residualBalance").get("amount").asString();
        if (new BigDecimal(residual).signum() > 0) {
            fixtures.payCash(sale, residual, residual).andExpect(status().isCreated());
        }
        JsonNode coverage = SettlementFixtures.json(fixtures.settlement(sale.cashier(), sale.order())).get("coverage");

        fixtures.close(sale.cashier(), sale.order(), fixtures.orderVersion(sale.cashier(), sale.order()),
                UUID.randomUUID().toString()).andExpect(status().isOk())
                .andExpect(jsonPath("$.lifecycle").value("CLOSED"));

        Money due = Money.of("0.00", MZN);
        Map<String, BigDecimal> lineTax = new TreeMap<>();
        for (JsonNode entry : coverage) {
            UUID line = UUID.fromString(entry.get("lineId").asString());
            BigDecimal net = new BigDecimal(entry.get("netQty").asString());
            Map<String, Object> priced = jdbc.sql("SELECT unit_price, tax_rate FROM orders.order_line WHERE id = ?")
                    .param(line).query().singleRow();
            BigDecimal rate = (BigDecimal) priced.get("tax_rate");
            LineAmounts amounts = LineAmounts.of(Money.of(((BigDecimal) priced.get("unit_price")).toPlainString(),
                    MZN), net, rate);
            due = due.plus(amounts.gross());
            lineTax.merge(key(rate), amounts.tax().amount(), BigDecimal::add);
            assertThat(fixtures.decimal("""
                    SELECT coalesce(sum(l.quantity), 0) FROM fiscal.fiscal_document_line l
                    JOIN fiscal.fiscal_document d ON d.id = l.document_id
                    WHERE d.order_ref = ? AND l.source_line_id = ? AND d.status = 'ISSUED'""", sale.order(), line))
                    .as("documented quantity of line %s", line).isEqualByComparingTo(net);
        }
        assertThat(fixtures.decimal("SELECT sum(total_gross) FROM fiscal.fiscal_document WHERE order_ref = ? "
                + "AND status = 'ISSUED'", sale.order())).as("Σ document gross = due").isEqualByComparingTo(
                due.amount());
        for (var rate : lineTax.entrySet()) {
            BigDecimal documents = fixtures.decimal("""
                    SELECT coalesce(sum(t.tax), 0) FROM fiscal.fiscal_document_tax t
                    JOIN fiscal.fiscal_document d ON d.id = t.document_id
                    WHERE d.order_ref = ? AND d.status = 'ISSUED' AND t.tax_rate = ?::numeric""", sale.order(),
                    rate.getKey());
            BigDecimal pending = fixtures.decimal("""
                    SELECT coalesce(sum(amount), 0) FROM settlement.tax_rounding_adjustment
                    WHERE order_id = ? AND kind = 'PENDING_CORRECTION' AND rate_percent = ?::numeric""",
                    sale.order(), rate.getKey());
            assertThat(documents).as("Σ document tax at %s %%", rate.getKey())
                    .isEqualByComparingTo(rate.getValue().subtract(pending));
        }
        for (UUID partition : cancelledIds) {
            assertThat(fixtures.count("SELECT count(*) FROM fiscal.fiscal_document WHERE source_id = ?", partition))
                    .as("a cancelled partition has no document").isZero();
        }
        List<String> kinds = jdbc.sql("SELECT kind FROM settlement.tax_rounding_adjustment WHERE order_id = ?")
                .param(sale.order()).query(String.class).list();
        return new Outcome(kinds, cancelledIds.size());
    }

    private BigDecimal poolOf(Sale sale, UUID line) throws Exception {
        for (JsonNode entry : SettlementFixtures.json(fixtures.settlement(sale.cashier(), sale.order()))
                .get("coverage")) {
            if (entry.get("lineId").asString().equals(line.toString())) {
                return new BigDecimal(entry.get("netQty").asString())
                        .subtract(new BigDecimal(entry.get("partitioned").asString()));
            }
        }
        throw new IllegalStateException("no line " + line);
    }

    private static String key(BigDecimal rate) {
        return rate.stripTrailingZeros().toPlainString();
    }
}
