package com.xavier.backend.inventory.count.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.xavier.backend.inventory.count.exception.CountConflictException;
import com.xavier.backend.inventory.count.exception.CountRuleException;
import com.xavier.backend.inventory.exception.InventoryNotFoundException;
import com.xavier.backend.inventory.exception.InventoryRuleException;
import com.xavier.backend.shared.exception.CodedException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The count machine (D119): who may do what in which status, and how a line is settled at its cut-off. */
class StockCountTest {

    static final Instant NOW = Instant.parse("2026-11-07T12:00:00Z");
    static final UUID STORE = UUID.randomUUID();
    static final UUID WAREHOUSE = UUID.randomUUID();
    static final UUID FLOUR = UUID.randomUUID();
    static final UUID OIL = UUID.randomUUID();

    @Test
    void approvalSettlesEachLineAgainstWhatWasExpectedAtItsCutOff() {
        StockCount count = started(CountKind.REGULAR, CountScope.ALL, Map.of());
        count.record(FLOUR, "g", new BigDecimal("7"), NOW.minusSeconds(60), null, "ACCOUNT", null, NOW);
        count.record(OIL, "ml", new BigDecimal("3"), NOW.minusSeconds(30), null, "ACCOUNT", null, NOW);
        count.submit(NOW);

        Map<UUID, BigDecimal> differences = count.approve(Map.of(FLOUR, new BigDecimal("9"), OIL,
                new BigDecimal("1")), UUID.randomUUID(), NOW);

        assertThat(differences.get(FLOUR)).isEqualByComparingTo("-2");
        assertThat(differences.get(OIL)).isEqualByComparingTo("2");
        assertThat(count.getStatus()).isEqualTo(CountStatus.APPROVED);
        assertThat(count.getLines()).extracting(StockCountLine::getExpectedQuantity)
                .usingElementComparator(BigDecimal::compareTo).containsExactlyInAnyOrder(new BigDecimal("9"),
                        BigDecimal.ONE);
    }

    @Test
    void linesAreRecordedOnlyWhileInProgress() {
        StockCount draft = StockCount.create(STORE, WAREHOUSE, CountKind.REGULAR, CountScope.ALL, Map.of(), null,
                NOW);
        assertCode(() -> draft.record(FLOUR, "g", BigDecimal.ONE, NOW, null, "ACCOUNT", null, NOW), "COUNT_STATE");

        StockCount submitted = started(CountKind.REGULAR, CountScope.ALL, Map.of());
        submitted.record(FLOUR, "g", BigDecimal.ONE, NOW, null, "ACCOUNT", null, NOW);
        submitted.submit(NOW);
        assertCode(() -> submitted.record(FLOUR, "g", BigDecimal.TWO, NOW, null, "ACCOUNT", null, NOW),
                "COUNT_STATE");
    }

    @Test
    void aLineIsNeverCountedInTheFuture() {
        StockCount count = started(CountKind.REGULAR, CountScope.ALL, Map.of());
        assertCode(() -> count.record(FLOUR, "g", BigDecimal.ONE, NOW.plusSeconds(1), null, "ACCOUNT", null, NOW),
                "COUNTED_AT_IN_FUTURE");
    }

    @Test
    void onlyAnOpeningCarriesAUnitCostAndItNeedsOne() {
        StockCount regular = started(CountKind.REGULAR, CountScope.ALL, Map.of());
        assertCode(() -> regular.record(FLOUR, "g", BigDecimal.ONE, NOW, BigDecimal.ONE, "ACCOUNT", null, NOW),
                "UNIT_COST_OPENING_ONLY");

        StockCount opening = started(CountKind.OPENING, CountScope.ALL, Map.of());
        assertThatThrownBy(() -> opening.record(FLOUR, "g", BigDecimal.ONE, NOW, null, "ACCOUNT", null, NOW))
                .isInstanceOf(InventoryRuleException.class).hasMessageContaining("unit cost");
        opening.record(OIL, "ml", BigDecimal.ZERO, NOW, null, "ACCOUNT", null, NOW); // nothing to value
    }

    @Test
    void aListCountTakesOnlyItsItemsAndEveryOneMustBeCounted() {
        Map<UUID, String> listed = new LinkedHashMap<>();
        listed.put(FLOUR, "g");
        listed.put(OIL, "ml");
        StockCount count = started(CountKind.REGULAR, CountScope.LIST, listed);

        assertThatThrownBy(() -> count.record(UUID.randomUUID(), "un", BigDecimal.ONE, NOW, null, "ACCOUNT", null,
                NOW)).isInstanceOf(InventoryNotFoundException.class);
        count.record(FLOUR, "g", BigDecimal.ONE, NOW, null, "ACCOUNT", null, NOW);
        assertCode(() -> count.submit(NOW), "COUNT_INCOMPLETE");

        count.record(OIL, "ml", BigDecimal.ONE, NOW, null, "ACCOUNT", null, NOW);
        count.submit(NOW);
        assertThat(count.getStatus()).isEqualTo(CountStatus.SUBMITTED);
    }

    @Test
    void anEmptyCountCannotBeSubmitted() {
        assertCode(() -> started(CountKind.REGULAR, CountScope.ALL, Map.of()).submit(NOW), "COUNT_INCOMPLETE");
    }

    @Test
    void theScopeMatchesTheItemsNamed() {
        assertCode(() -> StockCount.create(STORE, WAREHOUSE, CountKind.REGULAR, CountScope.LIST, Map.of(), null,
                NOW), "COUNT_SCOPE_INVALID");
        assertCode(() -> StockCount.create(STORE, WAREHOUSE, CountKind.REGULAR, CountScope.ALL, Map.of(FLOUR, "g"),
                null, NOW), "COUNT_SCOPE_INVALID");
    }

    @Test
    void aSubmittedCountIsDecidedOnceAndNeverCancelled() {
        StockCount count = started(CountKind.REGULAR, CountScope.ALL, Map.of());
        count.record(FLOUR, "g", BigDecimal.ONE, NOW, null, "ACCOUNT", null, NOW);
        count.submit(NOW);
        assertCode(() -> count.cancel(NOW), "COUNT_STATE");

        count.reject("recount the store-room", UUID.randomUUID(), NOW);

        assertThat(count.getStatus()).isEqualTo(CountStatus.REJECTED);
        assertCode(() -> count.approve(Map.of(FLOUR, BigDecimal.ONE), null, NOW), "COUNT_STATE");
        assertCode(() -> count.reject("again", null, NOW), "COUNT_STATE");
    }

    @Test
    void aLiveCountMayBeCancelledButNotStartedTwice() {
        StockCount count = started(CountKind.REGULAR, CountScope.ALL, Map.of());
        assertCode(() -> count.start(NOW), "COUNT_STATE");
        count.cancel(NOW);
        assertThat(count.getStatus()).isEqualTo(CountStatus.CANCELLED);
        assertCode(() -> count.start(NOW), "COUNT_STATE");
    }

    private static StockCount started(CountKind kind, CountScope scope, Map<UUID, String> listed) {
        StockCount count = StockCount.create(STORE, WAREHOUSE, kind, scope, listed, null, NOW);
        count.start(NOW);
        return count;
    }

    private static void assertCode(Runnable action, String code) {
        assertThatThrownBy(action::run).isInstanceOfAny(CountRuleException.class, CountConflictException.class)
                .extracting(e -> ((CodedException) e).code()).isEqualTo(code);
    }
}
