package com.xavier.backend.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.cash.CashFixtures;
import com.xavier.backend.reporting.service.Projections;
import com.xavier.backend.settlement.SettlementFixtures;
import com.xavier.backend.settlement.SettlementFixtures.Sale;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * Every report says how current and how final it is (D86, A28): while a projection is held, {@code pendingFacts} is
 * above zero and the report is not final; once nothing is pending and the day is FINANCIALLY_CLOSED, it is final.
 */
@ReportingModuleTest
class ReportFreshnessTest {

    @Autowired
    SettlementFixtures sales;

    @Autowired
    CashFixtures cash;

    @Autowired
    ReportingFixtures reporting;

    @Autowired
    Projections projections;

    @Test
    void aHeldProjectionIsPendingAndTheDayBecomesFinalOnceClosedAndProjected() throws Exception {
        Sale sale = sales.sale("2");
        LocalDate day = reporting.businessDayOf(sale.order());
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(call -> {
            held.countDown();
            release.await(30, TimeUnit.SECONDS);
            return call.callRealMethod();
        }).when(projections).projectPayment(any());

        try (var pool = Executors.newSingleThreadExecutor()) {
            // the listener may run on the paying thread after its commit, so the payment is sent from another one
            Future<?> paid = pool.submit(() -> {
                sales.payCash(sale, "25.00", "25.00").andExpect(status().isCreated());
                return null;
            });
            assertThat(held.await(20, TimeUnit.SECONDS)).as("the payment's projection started").isTrue();

            JsonNode whileHeld = reporting.report("receipts", sale.store(), day, day, "groupBy", "method");
            assertThat(whileHeld.get("pendingFacts").asLong()).isPositive();
            assertThat(whileHeld.get("final").asBoolean()).isFalse();
            assertThat(reporting.reportStatus(sale.store()).get("pendingFacts").asLong()).isPositive();

            release.countDown();
            paid.get(30, TimeUnit.SECONDS);
        } finally {
            release.countDown();
        }
        reporting.payments(sale.order()).forEach(reporting::awaitPaymentRecord);
        reporting.awaitNothingPendingFor(sale.store());
        assertThat(reporting.report("receipts", sale.store(), day, day, "groupBy", "method").get("final")
                .asBoolean()).as("the day is still open").isFalse();

        closeTheDayFinancially(sale);

        ReportingFixtures.await("no pending facts", () -> pendingFacts(sale, day) == 0);
        JsonNode closed = reporting.report("receipts", sale.store(), day, day, "groupBy", "method");
        assertThat(closed.get("final").asBoolean()).isTrue();
        assertThat(closed.get("asOf").isNull()).isFalse();
        assertThat(reporting.report("receipts", sale.store(), day, day.plusDays(1), "groupBy", "method")
                .get("final").asBoolean()).as("a date without a closed cash day").isFalse();
    }

    @Test
    void aRunningRebuildMakesTheRangeNotFinal() throws Exception {
        Sale sale = aFinalDay();
        LocalDate day = reporting.businessDayOf(sale.order());
        CountDownLatch held = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        doAnswer(call -> {
            held.countDown();
            release.await(30, TimeUnit.SECONDS);
            return call.callRealMethod();
        }).when(projections).clear(any(), any(), any());
        try {
            reporting.rebuild(sale.store(), day, day, UUID.randomUUID().toString()).andExpect(status().isAccepted());
            assertThat(held.await(20, TimeUnit.SECONDS)).as("the rebuild started").isTrue();

            assertThat(isFinal(sale, day)).as("while the rebuild runs").isFalse();
        } finally {
            release.countDown();
        }
        assertThat(reporting.awaitRebuildFinished(sale.store()).get("status").asString()).isEqualTo("COMPLETED");
        assertThat(isFinal(sale, day)).as("after it completed").isTrue();
    }

    @Test
    void aRebuildThatFailedPartwayKeepsTheRangeNotFinalUntilARebuildSucceeds() throws Exception {
        Sale sale = aFinalDay();
        LocalDate day = reporting.businessDayOf(sale.order());
        doThrow(new IllegalStateException("payment facts unavailable")).when(projections).projectPayment(any());
        reporting.rebuild(sale.store(), day, day, UUID.randomUUID().toString()).andExpect(status().isAccepted());
        assertThat(reporting.awaitRebuildFinished(sale.store()).get("status").asString()).isEqualTo("FAILED");

        assertThat(isFinal(sale, day)).as("after the failed rebuild").isFalse();

        doCallRealMethod().when(projections).projectPayment(any());
        reporting.rebuild(sale.store(), day, day, UUID.randomUUID().toString()).andExpect(status().isAccepted());
        assertThat(reporting.awaitRebuildFinished(sale.store()).get("status").asString()).isEqualTo("COMPLETED");
        assertThat(isFinal(sale, day)).as("after a successful rebuild").isTrue();
    }

    /** A sale paid, projected and closed, its day FINANCIALLY_CLOSED: the receipts report of the day is final. */
    private Sale aFinalDay() throws Exception {
        Sale sale = sales.sale("2");
        LocalDate day = reporting.businessDayOf(sale.order());
        sales.payCash(sale, "25.00", "25.00").andExpect(status().isCreated());
        reporting.payments(sale.order()).forEach(reporting::awaitPaymentRecord);
        closeTheDayFinancially(sale);
        reporting.awaitSalesRecord(reporting.issuedDocument(sale.order()));
        ReportingFixtures.await("no pending facts", () -> pendingFacts(sale, day) == 0);
        assertThat(isFinal(sale, day)).as("the closed day").isTrue();
        return sale;
    }

    private boolean isFinal(Sale sale, LocalDate day) throws Exception {
        return reporting.report("receipts", sale.store(), day, day, "groupBy", "method").get("final").asBoolean();
    }

    private void closeTheDayFinancially(Sale sale) throws Exception {
        sales.close(sale.cashier(), sale.order(), sales.orderVersion(sale.cashier(), sale.order()),
                UUID.randomUUID().toString()).andExpect(status().isOk());
        sales.closeSession(sale, "25.00");
        JsonNode day = sales.currentDay(sale.cashier());
        sales.closeDayAs(sale.cashier(), day).andExpect(status().isOk());
        UUID dayId = UUID.fromString(day.get("id").asString());
        cash.approveClosing(sale.store(), cash.submittedClosing(sale.cashier(), sale.register(), dayId));
        cash.closeDayFinancially(sale.store(), dayId);
    }

    private long pendingFacts(Sale sale, LocalDate day) {
        try {
            return reporting.report("receipts", sale.store(), day, day, "groupBy", "method").get("pendingFacts")
                    .asLong();
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }
}
