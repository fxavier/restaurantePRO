package com.xavier.backend.settlement.controller;

import static com.xavier.backend.cash.CashFixtures.idem;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.xavier.backend.access.Permission;
import com.xavier.backend.cash.CashApi;
import com.xavier.backend.cash.CashFixtures;
import com.xavier.backend.orders.OrdersApi;
import com.xavier.backend.settlement.SettlementFixtures;
import com.xavier.backend.settlement.SettlementFixtures.Sale;
import com.xavier.backend.settlement.SettlementModuleTest;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * D42 (b) and D51 for settlement's commands on an order or a day addressed by id: a POS session of another (active)
 * store is refused (403) after reading only the resource's store, so it neither waits for nor takes the order's
 * settlement lock, and loads neither the bill nor the day.
 */
@SettlementModuleTest
class StoreFirstAuthorizationHttpTest {

    private static final long LOCK_WAIT_SECONDS = 5;

    @Autowired
    MockMvc mvc;

    @Autowired
    SettlementFixtures fixtures;

    @Autowired
    CashFixtures cash;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    PlatformTransactionManager transactionManager;

    @Autowired
    OrdersApi orders;

    @Autowired
    CashApi cashApi;

    Sale sale;
    RequestPostProcessor stranger;
    ExecutorService threads;

    @BeforeEach
    void aSaleAndAStrangerFromAnotherActiveStore() throws Exception {
        threads = Executors.newFixedThreadPool(2);
        sale = fixtures.sale("2");
        stranger = CashFixtures.pos(cash.newStore()).with(Permission.PAYMENT_COLLECT, Permission.ORDER_VIEW,
                Permission.CASH_DAY_CLOSE);
    }

    @AfterEach
    void stopThreads() {
        threads.shutdownNow();
    }

    @Test
    void aStrangersCloseIs403WithoutLockingOrLoadingTheOrder() throws Exception {
        int status = mvc.perform(command(SettlementFixtures.ORDERS + "/" + sale.order() + "/close",
                "{\"orderVersion\": 1}").with(stranger)).andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(403);
        assertThat(cash.count("SELECT count(*) FROM settlement.order_settlement WHERE order_id = ?", sale.order()))
                .isZero();
        verify(orders, never()).billingView(sale.order());
    }

    @Test
    void aStrangersCloseDoesNotWaitForTheOrdersLock() throws Exception {
        assertThat(whileTheOrderIsLocked(command(SettlementFixtures.ORDERS + "/" + sale.order() + "/close",
                "{\"orderVersion\": 1}").with(stranger))).isEqualTo(403);
    }

    @Test
    void aStrangersDocumentRequestDoesNotWaitForTheOrdersLockNorLoadTheBill() throws Exception {
        assertThat(whileTheOrderIsLocked(command(SettlementFixtures.ORDERS + "/" + sale.order() + "/documents", "{}")
                .with(stranger))).isEqualTo(403);
        verify(orders, never()).billingView(sale.order());
    }

    @Test
    void aStrangersPaymentDoesNotWaitForTheOrdersLockNorLoadTheBill() throws Exception {
        assertThat(whileTheOrderIsLocked(command(SettlementFixtures.ATTEMPTS, """
                {"orderId": "%s", "methodCode": "CASH", "amount": 25.00, "tendered": 25.00}"""
                .formatted(sale.order())).with(stranger))).isEqualTo(403);
        verify(orders, never()).billingView(sale.order());
    }

    @Test
    void aStrangersSettlementReadDoesNotLoadTheBill() throws Exception {
        int status = mvc.perform(get(SettlementFixtures.ORDERS + "/" + sale.order() + "/settlement").with(stranger))
                .andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(403);
        verify(orders, never()).billingView(sale.order());
    }

    @Test
    void aStrangersDayCloseIs403WithoutLoadingTheDay() throws Exception {
        UUID day = UUID.fromString(fixtures.currentDay(sale.cashier()).get("id").asString());

        int status = mvc.perform(command("/api/v1/pos/cash/days/" + day + "/operational-close", "{\"version\": 0}")
                .with(stranger)).andReturn().getResponse().getStatus();

        assertThat(status).isEqualTo(403);
        verify(cashApi, never()).findDay(day);
        verify(cashApi, never()).lockDay(any(), any());
    }

    /**
     * Sends {@code request} while another transaction holds the order's settlement lock, and returns its status; a
     * request that waits for the lock fails the test instead of hanging it.
     */
    private int whileTheOrderIsLocked(MockHttpServletRequestBuilder request) throws Exception {
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Future<?> holder = threads.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(tx -> {
            jdbc.sql("INSERT INTO settlement.order_settlement (order_id, created_at) VALUES (?, now()) "
                    + "ON CONFLICT (order_id) DO NOTHING").param(sale.order()).update();
            jdbc.sql("SELECT order_id FROM settlement.order_settlement WHERE order_id = ? FOR UPDATE")
                    .param(sale.order()).query(UUID.class).single();
            locked.countDown();
            try {
                release.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }));
        try {
            assertThat(locked.await(LOCK_WAIT_SECONDS, TimeUnit.SECONDS)).isTrue();
            Future<Integer> response = threads.submit(() -> mvc.perform(request).andReturn().getResponse().getStatus());
            try {
                return response.get(LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
            } catch (TimeoutException waitedForTheLock) {
                throw new AssertionError("the request waited for the order's settlement lock");
            }
        } finally {
            release.countDown();
            holder.get(LOCK_WAIT_SECONDS, TimeUnit.SECONDS);
        }
    }

    private static MockHttpServletRequestBuilder command(String path, String body) {
        return idem(post(path)).contentType(MediaType.APPLICATION_JSON).content(body);
    }
}
