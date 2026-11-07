package com.xavier.backend.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.cash.CashFixtures.Pos;
import com.xavier.backend.orders.OrdersCatalog;
import com.xavier.backend.orders.OrdersFixtures;
import com.xavier.backend.reporting.CostKitchen.LiveShop;
import com.xavier.backend.settlement.SettlementFixtures;
import com.xavier.backend.settlement.SettlementFixtures.Sale;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * D131, D108: a menu consumes only its components, but its cost belongs to the item sold on the order line, the
 * menu. The day: one menu (9.50) of a soup (250 g of flour, 0.50) and a juice (100 g, 0.20), each component in a
 * family of its own. By item, the menu is one row with its net sales and the components' 0.70; by family, the cost
 * is in the menu's family.
 */
@ReportingModuleTest
class MenuMarginTest {

    @Autowired
    CostKitchen kitchen;

    @Autowired
    OrdersCatalog catalog;

    @Autowired
    OrdersFixtures orders;

    @Autowired
    SettlementFixtures sales;

    @Autowired
    ReportingFixtures reporting;

    LiveShop live;
    UUID menu;
    Sale sale;
    LocalDate day;

    @BeforeEach
    void aMenuOfTwoComponentsSoldOnce() throws Exception {
        live = kitchen.liveShop();
        UUID soup = OrdersFixtures.id(catalog.item(live.shop(), "KITCHEN", "4.00"));
        UUID juice = OrdersFixtures.id(catalog.item(live.shop(), "BAR", "3.00"));
        menu = OrdersFixtures.id(catalog.menu(live.shop(), "9.50", OrdersCatalog.group("STARTER", 1, 1, 0, soup),
                OrdersCatalog.group("DRINK", 1, 1, 1, juice)));
        long catalogVersion = orders.republish(live.shop().store());
        kitchen.recipe(live, soup, "250");
        kitchen.recipe(live, juice, "100");
        sale = kitchen.openSale(live.shop());
        Pos cashier = sale.cashier();
        UUID line = OrdersFixtures.id(OrdersFixtures.json(orders.addLine(live.shop(), sale.order(), """
                        {"itemId": "%s", "quantity": 1, "components": [{"groupCode": "STARTER", "itemId": "%s"},
                         {"groupCode": "DRINK", "itemId": "%s"}]}""".formatted(menu, soup, juice),
                cashier.employeeId(), cashier.terminalId(), UUID.randomUUID().toString())
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString()));
        orders.confirm(live.shop(), sale.order(), 0, catalogVersion, List.of(line), cashier.employeeId(),
                cashier.terminalId(), UUID.randomUUID().toString()).andExpect(status().isCreated());
        kitchen.awaitRows("consumption_record", live.shop().store(), 2);
        sales.pay(cashier, sale.order(), "CASH", "9.50", "9.50", UUID.randomUUID().toString())
                .andExpect(status().isCreated());
        sales.close(cashier, sale.order(), sales.orderVersion(cashier, sale.order()), UUID.randomUUID().toString())
                .andExpect(status().isOk());
        reporting.issuedDocuments(sale.order()).forEach(reporting::awaitSalesRecord);
        reporting.awaitNothingPendingFor(live.shop().store());
        day = reporting.businessDayOf(sale.order());
    }

    @AfterEach
    void retireTheMenu() throws Exception {
        catalog.removeCompanyWideData();
    }

    @Test
    void byItemTheMenuCarriesItsComponentsCost() throws Exception {
        JsonNode report = reporting.report("margins", live.shop().store(), day, day, "groupBy", "item");

        assertThat(report.get("rows")).hasSize(1);
        JsonNode row = report.get("rows").get(0);
        assertThat(row.get("key").asString()).isEqualTo(menu.toString());
        assertThat(row.get("netSales").get("amount").decimalValue()).isEqualByComparingTo(netSales());
        assertThat(row.get("costOfSales").get("amount").decimalValue()).isEqualByComparingTo("0.70");
    }

    @Test
    void byFamilyTheCostIsInTheMenusFamily() throws Exception {
        String menuFamily = (String) reporting.row("""
                SELECT family_code FROM reporting.sales_record_line WHERE store_id = ? AND item_id = ?""",
                live.shop().store(), menu).get("family_code");

        JsonNode report = reporting.report("margins", live.shop().store(), day, day, "groupBy", "family");

        assertThat(menuFamily).isNotNull();
        assertThat(report.get("rows")).hasSize(1);
        assertThat(report.get("rows").get(0).get("key").asString()).isEqualTo(menuFamily);
        assertThat(report.get("rows").get(0).get("costOfSales").get("amount").decimalValue())
                .isEqualByComparingTo("0.70");
    }

    private BigDecimal netSales() {
        return (BigDecimal) reporting.row("SELECT sum(net) AS net FROM reporting.sales_record_line WHERE store_id = ?",
                live.shop().store()).get("net");
    }
}
