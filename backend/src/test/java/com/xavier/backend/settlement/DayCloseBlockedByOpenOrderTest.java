package com.xavier.backend.settlement;

import static org.mockito.ArgumentMatchers.any;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.fiscal.adapter.internal.InternalFiscalAdapter;
import com.xavier.backend.settlement.SettlementFixtures.Sale;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * A11 (coordination half), spec §8.4 and D50 §7.8: a cash day does not close operationally while a session of it is
 * open, an order of it (or before) is open, or a fiscal draft is pending; once all are done it closes.
 */
@SettlementModuleTest
class DayCloseBlockedByOpenOrderTest {

    @Autowired
    SettlementFixtures fixtures;

    @Autowired
    InternalFiscalAdapter fiscalAdapter;

    @Autowired
    SettlementTestClock clock;

    @AfterEach
    void realAdapterAndClock() {
        Mockito.reset(fiscalAdapter);
        clock.reset();
    }

    @Test
    void anOpenOrderKeepsTheDayOpenUntilItIsClosed() throws Exception {
        Sale sale = fixtures.sale("2");
        fixtures.payCash(sale, "25.00", "25.00").andExpect(status().isCreated());
        fixtures.closeSession(sale, "25.00");

        fixtures.closeDay(sale.cashier()).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("OPEN_ORDERS"));

        fixtures.close(sale.cashier(), sale.order(), fixtures.orderVersion(sale.cashier(), sale.order()),
                UUID.randomUUID().toString()).andExpect(status().isOk());
        fixtures.closeDay(sale.cashier()).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPERATIONALLY_CLOSED"));
    }

    @Test
    void anOpenSessionKeepsTheDayOpen() throws Exception {
        Sale sale = fixtures.sale("2");
        fixtures.payAndClose(sale);

        fixtures.closeDay(sale.cashier()).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("OPEN_SESSIONS"));
    }

    @Test
    void aPendingFiscalDraftKeepsTheDayOpen() throws Exception {
        Sale sale = fixtures.sale("2");
        fixtures.payCash(sale, "25.00", "25.00").andExpect(status().isCreated());
        Mockito.doThrow(new IllegalStateException("adapter offline")).when(fiscalAdapter).issue(any());
        fixtures.issue(sale.cashier(), sale.order(), UUID.randomUUID().toString())
                .andExpect(jsonPath("$.code").value("FISCAL_PENDING"));
        fixtures.closeSession(sale, "25.00");

        fixtures.closeDay(sale.cashier()).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("FISCAL_PENDING"));
    }

    /** The store cutoff is 05:00 Africa/Maputo (03:00Z): the sale is on 4 November, the close attempt on the 5th. */
    @Test
    void aDayWorkedPastTheCutoffIsStillTheOneTheCashierCloses() throws Exception {
        clock.moveTo(Instant.parse("2026-11-05T02:59:00Z"));
        Sale sale = fixtures.sale("2");
        fixtures.payCash(sale, "25.00", "25.00").andExpect(status().isCreated());
        Mockito.doThrow(new IllegalStateException("adapter offline")).when(fiscalAdapter).issue(any());
        fixtures.issue(sale.cashier(), sale.order(), UUID.randomUUID().toString())
                .andExpect(jsonPath("$.code").value("FISCAL_PENDING"));
        fixtures.closeSession(sale, "25.00");

        clock.moveTo(Instant.parse("2026-11-05T03:01:00Z"));
        fixtures.closeDay(sale.cashier()).andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code").value("FISCAL_PENDING"));
    }

    @Test
    void aDayOfAnotherStoreIsNotYoursToClose() throws Exception {
        Sale sale = fixtures.sale("1");
        Sale other = fixtures.sale("1");
        var day = fixtures.currentDay(other.cashier());

        fixtures.closeDayAs(sale.cashier(), day).andExpect(status().isForbidden());
    }
}
