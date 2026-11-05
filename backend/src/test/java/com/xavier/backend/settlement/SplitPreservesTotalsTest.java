package com.xavier.backend.settlement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.orders.LineAmounts;
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
 * document gross = due, Σ document tax = Σ order line tax, Σ document quantity per line = net quantity.
 */
@SettlementModuleTest
class SplitPreservesTotalsTest {

    static final Money UNIT = Money.of("12.50", Currency.getInstance("MZN"));
    static final BigDecimal VAT = new BigDecimal("16");

    @Autowired
    SettlementFixtures fixtures;

    @Test
    void randomFractionalSplitsAndTheirDocumentsSumToTheOrder() throws Exception {
        Random random = new Random(1105);
        for (int scenario = 0; scenario < 4; scenario++) {
            splitAndClose(random);
        }
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
