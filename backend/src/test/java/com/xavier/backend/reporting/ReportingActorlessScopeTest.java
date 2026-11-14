package com.xavier.backend.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.xavier.backend.access.StoreScope;
import com.xavier.backend.access.StoreScopes;
import com.xavier.backend.fiscal.model.event.FiscalDocumentIssued;
import com.xavier.backend.inventory.model.event.ConsumptionWasteRecorded;
import com.xavier.backend.inventory.model.event.StockMovementsPosted;
import com.xavier.backend.orders.model.event.LineDiscountsRecorded;
import com.xavier.backend.orders.model.event.OrderClosed;
import com.xavier.backend.orders.model.event.OrderLinesVoided;
import com.xavier.backend.reporting.inventory.CostOfGoodsListeners;
import com.xavier.backend.reporting.service.CostProjections;
import com.xavier.backend.reporting.service.ProjectionListeners;
import com.xavier.backend.reporting.service.Projections;
import com.xavier.backend.settlement.SettlementFixtures;
import com.xavier.backend.settlement.SettlementFixtures.Sale;
import com.xavier.backend.settlement.model.event.CreditChargePosted;
import com.xavier.backend.settlement.model.event.PaymentRecorded;
import com.xavier.backend.settlement.model.event.RefundRecorded;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.databind.JsonNode;

/**
 * A74 (S69 part, D166): reporting's actor-less entry points run in their scope. Each of the nine projection and
 * cost-of-goods listener methods runs, on its listener thread, in its event's store; a live sale is projected (written)
 * under that scope; a rebuild runs on the executor in the requesting account's scope ({@code task:account:…}). The
 * scope is read with {@link StoreScopes#current()} inside the spied projection a listener calls. Listener and executor
 * threads never get the fixture fallback, so this class needs no {@code @StrictStoreScope} (its fixtures run on the
 * JUnit thread). The readiness hook ({@code REPORTING_RECOVERY}) is covered by {@code ActorlessStoreScopeTest}.
 */
@ReportingModuleTest
class ReportingActorlessScopeTest {

    record Seen(String method, List<Object> arguments, StoreScope scope) {
    }

    @Autowired
    ProjectionListeners projectionListeners;

    @Autowired
    CostOfGoodsListeners costListeners;

    @Autowired
    Projections projections;

    @Autowired
    CostProjections costs;

    @Autowired
    StoreScopes scopes;

    @Autowired
    SettlementFixtures sales;

    @Autowired
    ReportingFixtures reporting;

    final List<Seen> seen = new CopyOnWriteArrayList<>();

    @BeforeEach
    void forgetTheScopesSeen() {
        seen.clear();
    }

    static Stream<Arguments> listeners() {
        return Stream.of(
                listener("ProjectionListeners.on", "projectDocument",
                        (t, store, id) -> t.projectionListeners.on(new FiscalDocumentIssued(id, store, null, null))),
                listener("ProjectionListeners.on", "projectVoid",
                        (t, store, id) -> t.projectionListeners.on(new OrderLinesVoided(UUID.randomUUID(), id,
                                store, null, 1, null, null, null, null, null, null))),
                listener("ProjectionListeners.on", "projectPayment",
                        (t, store, id) -> t.projectionListeners.on(new PaymentRecorded(id, store))),
                listener("ProjectionListeners.on", "projectRefund",
                        (t, store, id) -> t.projectionListeners.on(new RefundRecorded(id, store))),
                listener("ProjectionListeners.on", "projectCreditCharge",
                        (t, store, id) -> t.projectionListeners.on(new CreditChargePosted(id, store))),
                listener("CostOfGoodsListeners.on", "projectStockSource",
                        (t, store, id) -> t.costListeners.on(new StockMovementsPosted("ROUND", id, store,
                                LocalDate.of(2026, 1, 5)))),
                listener("CostOfGoodsListeners.on", "projectWaste",
                        (t, store, id) -> t.costListeners.on(new ConsumptionWasteRecorded(id, UUID.randomUUID(),
                                store, null, null, null, null, null, null, null))),
                listener("CostOfGoodsListeners.on", "projectDiscounts",
                        (t, store, id) -> t.costListeners.on(new LineDiscountsRecorded(UUID.randomUUID(), store,
                                LineDiscountsRecorded.BATCH, id))),
                listener("CostOfGoodsListeners.on", "projectOfferCosts",
                        (t, store, id) -> t.costListeners.on(new OrderClosed(id, store, LocalDate.of(2026, 1, 5)))));
    }

    @ParameterizedTest(name = "{1}")
    @MethodSource("listeners")
    void everyListenerRunsInItsEventsStoreOnItsListenerThread(String listener, String projection,
                                                              Delivery deliver) {
        recordEveryProjection(false);
        UUID store = UUID.randomUUID();
        UUID source = UUID.randomUUID();

        deliver.to(this, store, source);

        Seen called = awaitSeen(projection, source);
        assertThat(called.scope()).isEqualTo(StoreScope.ofStore(store, "listener:" + listener));
    }

    /** The live path end to end: the sale's document and closed order are projected (written) in its store. */
    @Test
    void aLiveSaleIsProjectedInItsStoresScope() throws Exception {
        recordEveryProjection(true);
        Sale sale = sales.sale("2"); // payAndClose pays 25.00 = 2 × 12.50
        sales.payAndClose(sale);
        UUID document = reporting.issuedDocument(sale.order());

        reporting.awaitSalesRecord(document);

        assertThat(awaitSeen("projectDocument", document).scope())
                .isEqualTo(StoreScope.ofStore(sale.store(), "listener:ProjectionListeners.on"));
        assertThat(awaitSeen("projectOfferCosts", sale.order()).scope())
                .isEqualTo(StoreScope.ofStore(sale.store(), "listener:CostOfGoodsListeners.on"));
    }

    /** The rebuild runner runs on the executor in the requesting account's stores, and completes. */
    @Test
    void aRebuildRunsInTheRequestingAccountsScopeAndCompletes() throws Exception {
        Sale sale = sales.sale("2"); // payAndClose pays 25.00 = 2 × 12.50
        sales.payAndClose(sale);
        reporting.awaitNothingPendingFor(sale.store());
        LocalDate day = reporting.businessDayOf(sale.order());
        recordEveryProjection(true);

        reporting.rebuild(sale.store(), day, day, UUID.randomUUID().toString()).andExpect(status().isAccepted());
        JsonNode run = reporting.awaitRebuildFinished(sale.store());

        assertThat(run.get("status").asString()).isEqualTo("COMPLETED");
        StoreScope scope = awaitSeen("clear", sale.store()).scope();
        assertThat(scope.allStores()).isFalse();
        assertThat(scope.stores()).isEqualTo(Set.of(sale.store()));
        assertThat(scope.origin()).startsWith("task:account:");
        assertThat(awaitSeen("projectDocument", reporting.issuedDocument(sale.order())).scope()).isEqualTo(scope);
    }

    /** Records the scope of every projection call; {@code real} also runs the projection, else it does nothing. */
    private void recordEveryProjection(boolean real) {
        doAnswer(record("projectDocument", real)).when(projections).projectDocument(any());
        doAnswer(record("projectVoid", real)).when(projections).projectVoid(any());
        doAnswer(record("projectPayment", real)).when(projections).projectPayment(any());
        doAnswer(record("projectRefund", real)).when(projections).projectRefund(any());
        doAnswer(record("projectCreditCharge", real)).when(projections).projectCreditCharge(any());
        doAnswer(record("clear", real)).when(projections).clear(any(), any(), any());
        doAnswer(record("projectStockSource", real)).when(costs).projectStockSource(any(), any());
        doAnswer(record("projectWaste", real)).when(costs).projectWaste(any());
        doAnswer(record("projectDiscounts", real)).when(costs).projectDiscounts(any(), any());
        doAnswer(record("projectOfferCosts", real)).when(costs).projectOfferCosts(any());
    }

    private Answer<Object> record(String method, boolean real) {
        return call -> {
            seen.add(new Seen(method, Arrays.asList(call.getArguments()), scopes.current()));
            return real ? call.callRealMethod() : null;
        };
    }

    /** The first call of {@code method} with {@code argument} among its arguments. */
    private Seen awaitSeen(String method, Object argument) {
        ReportingFixtures.await(method + " called", () -> find(method, argument) != null);
        return find(method, argument);
    }

    private Seen find(String method, Object argument) {
        return seen.stream().filter(call -> call.method().equals(method))
                .filter(call -> call.arguments().contains(argument)).findFirst().orElse(null);
    }

    private static Arguments listener(String listener, String projection, Delivery deliver) {
        return Arguments.of(listener, projection, deliver);
    }

    /** Calls one listener method directly (through its proxy) with an event of {@code store} naming {@code source}. */
    @FunctionalInterface
    interface Delivery {
        void to(ReportingActorlessScopeTest test, UUID store, UUID source);
    }
}
