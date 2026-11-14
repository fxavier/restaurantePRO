package com.xavier.backend.inventory.consumption;

import com.xavier.backend.access.StoreScopes;
import com.xavier.backend.catalog.CatalogApi;
import com.xavier.backend.inventory.InventoryFixtures;
import com.xavier.backend.inventory.consumption.service.RoundConsumption;
import com.xavier.backend.inventory.consumption.service.VoidReversal;
import com.xavier.backend.inventory.service.StockLedger;
import com.xavier.backend.organization.MutableClock;
import com.xavier.backend.orders.OrdersApi;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionTemplate;

/** What the consumption tests of the inventory context share: a fresh {@link Kitchen} per test. */
abstract class ConsumptionTestBase {

    @Autowired
    MockMvc mvc;

    @Autowired
    InventoryFixtures fixtures;

    @Autowired
    StockLedger ledger;

    @Autowired
    TransactionTemplate tx;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    OrdersApi orders;

    @Autowired
    CatalogApi catalog;

    @Autowired
    MutableClock clock;

    @Autowired
    RoundConsumption consumption;

    @Autowired
    VoidReversal voids;

    @Autowired
    StoreScopes scopes;

    Kitchen kitchen;

    @BeforeEach
    void openKitchen() throws Exception {
        kitchen = Kitchen.open(mvc, fixtures, ledger, tx, jdbc, orders, catalog);
    }
}
